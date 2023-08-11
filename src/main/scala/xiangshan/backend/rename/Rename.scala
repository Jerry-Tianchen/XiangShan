/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.backend.rename

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utility._
import utils._
import xiangshan._
import xiangshan.backend.decode.{FusionDecodeInfo, Imm_I, Imm_LUI_LOAD, Imm_U}
import xiangshan.backend.fu.FuType
import xiangshan.backend.rename.freelist._
import xiangshan.backend.rob.RobPtr
import xiangshan.backend.rename.freelist._
import xiangshan.mem.mdp._
import xiangshan.backend.Bundles.{DecodedInst, DynInst}

class Rename(implicit p: Parameters) extends XSModule with HasCircularQueuePtrHelper with HasPerfEvents {

  // params alias
  private val numRegSrc = backendParams.numRegSrc
  private val numVecRegSrc = backendParams.numVecRegSrc
  private val numVecRatPorts = numVecRegSrc + 1 // +1 dst

  println(s"[Rename] numRegSrc: $numRegSrc")

  val io = IO(new Bundle() {
    val redirect = Flipped(ValidIO(new Redirect))
    val robCommits = Input(new RobCommitIO)
    // from decode
    val in = Vec(RenameWidth, Flipped(DecoupledIO(new DecodedInst)))
    val fusionInfo = Vec(DecodeWidth - 1, Flipped(new FusionDecodeInfo))
    // ssit read result
    val ssit = Flipped(Vec(RenameWidth, Output(new SSITEntry)))
    // waittable read result
    val waittable = Flipped(Vec(RenameWidth, Output(Bool())))
    // to rename table
    val intReadPorts = Vec(RenameWidth, Vec(3, Input(UInt(PhyRegIdxWidth.W))))
    val fpReadPorts = Vec(RenameWidth, Vec(4, Input(UInt(PhyRegIdxWidth.W))))
    val vecReadPorts = Vec(RenameWidth, Vec(numVecRatPorts, Input(UInt(PhyRegIdxWidth.W))))
    val intRenamePorts = Vec(RenameWidth, Output(new RatWritePort))
    val fpRenamePorts = Vec(RenameWidth, Output(new RatWritePort))
    val vecRenamePorts = Vec(RenameWidth, Output(new RatWritePort))
    // from rename table
    val int_old_pdest = Vec(CommitWidth, Input(UInt(PhyRegIdxWidth.W)))
    val fp_old_pdest = Vec(CommitWidth, Input(UInt(PhyRegIdxWidth.W)))
    val int_need_free = Vec(CommitWidth, Input(Bool()))
    // to dispatch1
    val out = Vec(RenameWidth, DecoupledIO(new DynInst))
    // for snapshots
    val snpt = Input(new SnapshotPort)
    // debug arch ports
    val debug_int_rat = Vec(32, Input(UInt(PhyRegIdxWidth.W)))
    val debug_vconfig_rat = Input(UInt(PhyRegIdxWidth.W))
    val debug_fp_rat = Vec(32, Input(UInt(PhyRegIdxWidth.W)))
    val debug_vec_rat = Vec(32, Input(UInt(PhyRegIdxWidth.W)))
    // perf only
    val stallReason = new Bundle {
      val in = Flipped(new StallReasonIO(RenameWidth))
      val out = new StallReasonIO(RenameWidth)
    }
  })

  // create free list and rat
  val intFreeList = Module(new MEFreeList(IntPhyRegs))
  val fpFreeList = Module(new StdFreeList(VfPhyRegs - FpLogicRegs - VecLogicRegs))

  intFreeList.io.commit    <> io.robCommits
  intFreeList.io.debug_rat <> io.debug_int_rat
  fpFreeList.io.commit     <> io.robCommits
  fpFreeList.io.debug_rat  <> io.debug_fp_rat

  // decide if given instruction needs allocating a new physical register (CfCtrl: from decode; RobCommitInfo: from rob)
  // fp and vec share `fpFreeList`
  def needDestReg[T <: DecodedInst](reg_t: RegType, x: T): Bool = reg_t match {
    case Reg_I => x.rfWen && x.ldest =/= 0.U
    case Reg_F => x.fpWen
    case Reg_V => x.vecWen
  }
  def needDestRegCommit[T <: RobCommitInfo](reg_t: RegType, x: T): Bool = {
    reg_t match {
      case Reg_I => x.rfWen
      case Reg_F => x.fpWen
      case Reg_V => x.vecWen
    }
  }
  def needDestRegWalk[T <: RobCommitInfo](reg_t: RegType, x: T): Bool = {
    reg_t match {
      case Reg_I => x.rfWen && x.ldest =/= 0.U
      case Reg_F => x.fpWen
      case Reg_V => x.vecWen
    }
  }

  // connect [redirect + walk] ports for __float point__ & __integer__ free list
  Seq(fpFreeList, intFreeList).foreach { case fl =>
    fl.io.redirect := io.redirect.valid
    fl.io.walk := io.robCommits.isWalk
  }
  // only when both fp and int free list and dispatch1 has enough space can we do allocation
  // when isWalk, freelist can definitely allocate
  intFreeList.io.doAllocate := fpFreeList.io.canAllocate && io.out(0).ready || io.robCommits.isWalk
  fpFreeList.io.doAllocate := intFreeList.io.canAllocate && io.out(0).ready || io.robCommits.isWalk

  //           dispatch1 ready ++ float point free list ready ++ int free list ready      ++ not walk
  val canOut = io.out(0).ready && fpFreeList.io.canAllocate && intFreeList.io.canAllocate && !io.robCommits.isWalk


  // speculatively assign the instruction with an robIdx
  val validCount = PopCount(io.in.map(in => in.valid && in.bits.lastUop)) // number of instructions waiting to enter rob (from decode)
  val robIdxHead = RegInit(0.U.asTypeOf(new RobPtr))
  val lastCycleMisprediction = RegNext(io.redirect.valid && !io.redirect.bits.flushItself())
  val robIdxHeadNext = Mux(io.redirect.valid, io.redirect.bits.robIdx, // redirect: move ptr to given rob index
         Mux(lastCycleMisprediction, robIdxHead + 1.U, // mis-predict: not flush robIdx itself
                         Mux(canOut, robIdxHead + validCount, // instructions successfully entered next stage: increase robIdx
                      /* default */  robIdxHead))) // no instructions passed by this cycle: stick to old value
  robIdxHead := robIdxHeadNext

  /**
    * Rename: allocate free physical register and update rename table
    */
  val uops = Wire(Vec(RenameWidth, new DynInst))
  uops.foreach( uop => {
    uop.srcState      := DontCare
    uop.robIdx        := DontCare
    uop.debugInfo     := DontCare
    uop.lqIdx         := DontCare
    uop.sqIdx         := DontCare
    uop.waitForRobIdx := DontCare
    uop.singleStep    := DontCare
    uop.snapshot      := DontCare
  })

  require(RenameWidth >= CommitWidth)
  val needVecDest    = Wire(Vec(RenameWidth, Bool()))
  val needFpDest     = Wire(Vec(RenameWidth, Bool()))
  val needIntDest    = Wire(Vec(RenameWidth, Bool()))
  val hasValid = Cat(io.in.map(_.valid)).orR

  val isMove = io.in.map(_.bits.isMove)

  val walkNeedIntDest = WireDefault(VecInit(Seq.fill(RenameWidth)(false.B)))
  val walkNeedFpDest = WireDefault(VecInit(Seq.fill(RenameWidth)(false.B)))
  val walkNeedVecDest = WireDefault(VecInit(Seq.fill(RenameWidth)(false.B)))
  val walkIsMove = WireDefault(VecInit(Seq.fill(RenameWidth)(false.B)))

  val intSpecWen = Wire(Vec(RenameWidth, Bool()))
  val fpSpecWen  = Wire(Vec(RenameWidth, Bool()))
  val vecSpecWen = Wire(Vec(RenameWidth, Bool()))

  val walkIntSpecWen = WireDefault(VecInit(Seq.fill(RenameWidth)(false.B)))

  val walkPdest = Wire(Vec(RenameWidth, UInt(PhyRegIdxWidth.W)))

  // uop calculation
  for (i <- 0 until RenameWidth) {
    for ((name, data) <- uops(i).elements) {
      if (io.in(i).bits.elements.contains(name)) {
        data := io.in(i).bits.elements(name)
      }
    }

    // update cf according to ssit result
    uops(i).storeSetHit := io.ssit(i).valid
    uops(i).loadWaitStrict := io.ssit(i).strict && io.ssit(i).valid
    uops(i).ssid := io.ssit(i).ssid

    // update cf according to waittable result
    uops(i).loadWaitBit := io.waittable(i)

    uops(i).replayInst := false.B // set by IQ or MemQ
    // alloc a new phy reg, fp and vec share the `fpFreeList`
    needVecDest   (i) := io.in(i).valid && needDestReg(Reg_V,       io.in(i).bits)
    needFpDest    (i) := io.in(i).valid && needDestReg(Reg_F,       io.in(i).bits)
    needIntDest   (i) := io.in(i).valid && needDestReg(Reg_I,       io.in(i).bits)
    if (i < CommitWidth) {
      walkNeedIntDest(i) := io.robCommits.walkValid(i) && needDestRegWalk(Reg_I, io.robCommits.info(i))
      walkNeedFpDest(i) := io.robCommits.walkValid(i) && needDestRegWalk(Reg_F, io.robCommits.info(i))
      walkNeedVecDest(i) := io.robCommits.walkValid(i) && needDestRegWalk(Reg_V, io.robCommits.info(i))
      walkIsMove(i) := io.robCommits.info(i).isMove
    }
    fpFreeList.io.allocateReq(i) := needFpDest(i) || needVecDest(i)
    fpFreeList.io.walkReq(i) := walkNeedFpDest(i) || walkNeedVecDest(i)
    intFreeList.io.allocateReq(i) := needIntDest(i) && !isMove(i)
    intFreeList.io.walkReq(i) := walkNeedIntDest(i) && !walkIsMove(i)

    // no valid instruction from decode stage || all resources (dispatch1 + both free lists) ready
    io.in(i).ready := !hasValid || canOut

    uops(i).robIdx := robIdxHead + PopCount(io.in.take(i).map(in => in.valid && in.bits.lastUop))

    uops(i).psrc(0) := Mux1H(uops(i).srcType(0), Seq(io.intReadPorts(i)(0), io.fpReadPorts(i)(0), io.vecReadPorts(i)(0)))
    uops(i).psrc(1) := Mux1H(uops(i).srcType(1), Seq(io.intReadPorts(i)(1), io.fpReadPorts(i)(1), io.vecReadPorts(i)(1)))
    uops(i).psrc(2) := Mux1H(uops(i).srcType(2)(2, 1), Seq(io.fpReadPorts(i)(2), io.vecReadPorts(i)(2)))
    uops(i).psrc(3) := io.vecReadPorts(i)(3)
    uops(i).psrc(4) := io.vecReadPorts(i)(4) // Todo: vl read port

    // int psrc2 should be bypassed from next instruction if it is fused
    if (i < RenameWidth - 1) {
      when (io.fusionInfo(i).rs2FromRs2 || io.fusionInfo(i).rs2FromRs1) {
        uops(i).psrc(1) := Mux(io.fusionInfo(i).rs2FromRs2, io.intReadPorts(i + 1)(1), io.intReadPorts(i + 1)(0))
      }.elsewhen(io.fusionInfo(i).rs2FromZero) {
        uops(i).psrc(1) := 0.U
      }
    }
    uops(i).psrc(2) := io.fpReadPorts(i)(2)
    // Todo
    // uops(i).old_pdest := Mux(uops(i).ctrl.rfWen, io.intReadPorts(i).last, io.fpReadPorts(i).last)
    uops(i).eliminatedMove := isMove(i)

    // update pdest
    uops(i).pdest := MuxCase(0.U, Seq(
      needIntDest(i)                    -> intFreeList.io.allocatePhyReg(i),
      (needFpDest(i) || needVecDest(i)) -> fpFreeList.io.allocatePhyReg(i),
    ))

    // Assign performance counters
    uops(i).debugInfo.renameTime := GTimer()

    io.out(i).valid := io.in(i).valid && intFreeList.io.canAllocate && fpFreeList.io.canAllocate && !io.robCommits.isWalk
    io.out(i).bits := uops(i)
    // Todo: move these shit in decode stage
    // dirty code for fence. The lsrc is passed by imm.
    when (io.out(i).bits.fuType === FuType.fence.U) {
      io.out(i).bits.imm := Cat(io.in(i).bits.lsrc(1), io.in(i).bits.lsrc(0))
    }

    // dirty code for SoftPrefetch (prefetch.r/prefetch.w)
//    when (io.in(i).bits.isSoftPrefetch) {
//      io.out(i).bits.fuType := FuType.ldu.U
//      io.out(i).bits.fuOpType := Mux(io.in(i).bits.lsrc(1) === 1.U, LSUOpType.prefetch_r, LSUOpType.prefetch_w)
//      io.out(i).bits.selImm := SelImm.IMM_S
//      io.out(i).bits.imm := Cat(io.in(i).bits.imm(io.in(i).bits.imm.getWidth - 1, 5), 0.U(5.W))
//    }

    // write speculative rename table
    // we update rat later inside commit code
    intSpecWen(i) := needIntDest(i) && intFreeList.io.canAllocate && intFreeList.io.doAllocate && !io.robCommits.isWalk && !io.redirect.valid
    fpSpecWen(i) := needFpDest(i) && fpFreeList.io.canAllocate && fpFreeList.io.doAllocate && !io.robCommits.isWalk && !io.redirect.valid
    vecSpecWen(i) := needVecDest(i) && fpFreeList.io.canAllocate && fpFreeList.io.doAllocate && !io.robCommits.isWalk && !io.redirect.valid

    if (i < CommitWidth) {
      walkIntSpecWen(i) := walkNeedIntDest(i) && !io.redirect.valid
      walkPdest(i) := io.robCommits.info(i).pdest
    } else {
      walkPdest(i) := io.out(i).bits.pdest
    }
  }

  /**
    * How to set psrc:
    * - bypass the pdest to psrc if previous instructions write to the same ldest as lsrc
    * - default: psrc from RAT
    * How to set pdest:
    * - Mux(isMove, psrc, pdest_from_freelist).
    *
    * The critical path of rename lies here:
    * When move elimination is enabled, we need to update the rat with psrc.
    * However, psrc maybe comes from previous instructions' pdest, which comes from freelist.
    *
    * If we expand these logic for pdest(N):
    * pdest(N) = Mux(isMove(N), psrc(N), freelist_out(N))
    *          = Mux(isMove(N), Mux(bypass(N, N - 1), pdest(N - 1),
    *                           Mux(bypass(N, N - 2), pdest(N - 2),
    *                           ...
    *                           Mux(bypass(N, 0),     pdest(0),
    *                                                 rat_out(N))...)),
    *                           freelist_out(N))
    */
  // a simple functional model for now
  io.out(0).bits.pdest := Mux(isMove(0), uops(0).psrc.head, uops(0).pdest)

  // psrc(n) + pdest(1)
  val bypassCond: Vec[MixedVec[UInt]] = Wire(Vec(numRegSrc + 1, MixedVec(List.tabulate(RenameWidth-1)(i => UInt((i+1).W)))))
  require(io.in(0).bits.srcType.size == io.in(0).bits.numSrc)
  private val pdestLoc = io.in.head.bits.srcType.size // 2 vector src: v0, vl&vtype
  println(s"[Rename] idx of pdest in bypassCond $pdestLoc")
  for (i <- 1 until RenameWidth) {
    val vecCond = io.in(i).bits.srcType.map(_ === SrcType.vp) :+ needVecDest(i)
    val fpCond  = io.in(i).bits.srcType.map(_ === SrcType.fp) :+ needFpDest(i)
    val intCond = io.in(i).bits.srcType.map(_ === SrcType.xp) :+ needIntDest(i)
    val target = io.in(i).bits.lsrc :+ io.in(i).bits.ldest
    for (((((cond1, cond2), cond3), t), j) <- vecCond.zip(fpCond).zip(intCond).zip(target).zipWithIndex) {
      val destToSrc = io.in.take(i).zipWithIndex.map { case (in, j) =>
        val indexMatch = in.bits.ldest === t
        val writeMatch =  cond3 && needIntDest(j) || cond2 && needFpDest(j) || cond1 && needVecDest(j)
        indexMatch && writeMatch
      }
      bypassCond(j)(i - 1) := VecInit(destToSrc).asUInt
    }
    io.out(i).bits.psrc(0) := io.out.take(i).map(_.bits.pdest).zip(bypassCond(0)(i-1).asBools).foldLeft(uops(i).psrc(0)) {
      (z, next) => Mux(next._2, next._1, z)
    }
    io.out(i).bits.psrc(1) := io.out.take(i).map(_.bits.pdest).zip(bypassCond(1)(i-1).asBools).foldLeft(uops(i).psrc(1)) {
      (z, next) => Mux(next._2, next._1, z)
    }
    io.out(i).bits.psrc(2) := io.out.take(i).map(_.bits.pdest).zip(bypassCond(2)(i-1).asBools).foldLeft(uops(i).psrc(2)) {
      (z, next) => Mux(next._2, next._1, z)
    }
    io.out(i).bits.psrc(3) := io.out.take(i).map(_.bits.pdest).zip(bypassCond(3)(i-1).asBools).foldLeft(uops(i).psrc(3)) {
      (z, next) => Mux(next._2, next._1, z)
    }
    io.out(i).bits.psrc(4) := io.out.take(i).map(_.bits.pdest).zip(bypassCond(4)(i-1).asBools).foldLeft(uops(i).psrc(4)) {
      (z, next) => Mux(next._2, next._1, z)
    }
    io.out(i).bits.pdest := Mux(isMove(i), io.out(i).bits.psrc(0), uops(i).pdest)

    // Todo: better implementation for fields reuse
    // For fused-lui-load, load.src(0) is replaced by the imm.
    val last_is_lui = io.in(i - 1).bits.selImm === SelImm.IMM_U && io.in(i - 1).bits.srcType(0) =/= SrcType.pc
    val this_is_load = io.in(i).bits.fuType === FuType.ldu.U
    val lui_to_load = io.in(i - 1).valid && io.in(i - 1).bits.ldest === io.in(i).bits.lsrc(0)
    val fused_lui_load = last_is_lui && this_is_load && lui_to_load && false.B // Todo: enable it
    when (fused_lui_load) {
      // The first LOAD operand (base address) is replaced by LUI-imm and stored in {psrc, imm}
      val lui_imm = io.in(i - 1).bits.imm(19, 0)
      val ld_imm = io.in(i).bits.imm
      io.out(i).bits.srcType(0) := SrcType.imm
      io.out(i).bits.imm := Imm_LUI_LOAD().immFromLuiLoad(lui_imm, ld_imm)
      val psrcWidth = uops(i).psrc.head.getWidth
      val lui_imm_in_imm = 20/*Todo: uops(i).imm.getWidth*/ - Imm_I().len
      val left_lui_imm = Imm_U().len - lui_imm_in_imm
      require(2 * psrcWidth >= left_lui_imm, "cannot fused lui and load with psrc")
      io.out(i).bits.psrc(0) := lui_imm(lui_imm_in_imm + psrcWidth - 1, lui_imm_in_imm)
      io.out(i).bits.psrc(1) := lui_imm(lui_imm.getWidth - 1, lui_imm_in_imm + psrcWidth)
    }

  }

  val hasCFI = VecInit(io.in.map(in => (!in.bits.preDecodeInfo.notCFI || FuType.isJump(in.bits.fuType)) && in.fire)).asUInt.orR
  val snapshotCtr = RegInit((4 * CommitWidth).U)
  val allowSnpt = if (EnableRenameSnapshot) !snapshotCtr.orR else false.B
  io.out.head.bits.snapshot := hasCFI && allowSnpt
  when(io.out.head.fire && io.out.head.bits.snapshot) {
    snapshotCtr := (4 * CommitWidth).U - PopCount(io.out.map(_.fire))
  }.elsewhen(io.out.head.fire) {
    snapshotCtr := Mux(snapshotCtr < PopCount(io.out.map(_.fire)), 0.U, snapshotCtr - PopCount(io.out.map(_.fire)))
  }

  intFreeList.io.snpt := io.snpt
  fpFreeList.io.snpt := io.snpt
  intFreeList.io.snpt.snptEnq := io.out.head.fire && io.out.head.bits.snapshot
  fpFreeList.io.snpt.snptEnq := io.out.head.fire && io.out.head.bits.snapshot

  /**
    * Instructions commit: update freelist and rename table
    */
  for (i <- 0 until CommitWidth) {
    val commitValid = io.robCommits.isCommit && io.robCommits.commitValid(i)
    val walkValid = io.robCommits.isWalk && io.robCommits.walkValid(i)

    // I. RAT Update
    // When redirect happens (mis-prediction), don't update the rename table
    io.intRenamePorts(i).wen  := intSpecWen(i)
    io.intRenamePorts(i).addr := uops(i).ldest
    io.intRenamePorts(i).data := io.out(i).bits.pdest

    io.fpRenamePorts(i).wen  := fpSpecWen(i)
    io.fpRenamePorts(i).addr := uops(i).ldest
    io.fpRenamePorts(i).data := fpFreeList.io.allocatePhyReg(i)

    io.vecRenamePorts(i).wen := vecSpecWen(i)
    io.vecRenamePorts(i).addr := uops(i).ldest
    io.vecRenamePorts(i).data := fpFreeList.io.allocatePhyReg(i)

    // II. Free List Update
    intFreeList.io.freeReq(i) := io.int_need_free(i)
    intFreeList.io.freePhyReg(i) := RegNext(io.int_old_pdest(i))
    fpFreeList.io.freeReq(i)  := RegNext(commitValid && (needDestRegCommit(Reg_F, io.robCommits.info(i)) || needDestRegCommit(Reg_V, io.robCommits.info(i))))
    fpFreeList.io.freePhyReg(i) := io.fp_old_pdest(i)
  }

  /*
  Debug and performance counters
   */
  def printRenameInfo(in: DecoupledIO[DecodedInst], out: DecoupledIO[DynInst]) = {
    XSInfo(out.fire, p"pc:${Hexadecimal(in.bits.pc)} in(${in.valid},${in.ready}) " +
      p"lsrc(0):${in.bits.lsrc(0)} -> psrc(0):${out.bits.psrc(0)} " +
      p"lsrc(1):${in.bits.lsrc(1)} -> psrc(1):${out.bits.psrc(1)} " +
      p"lsrc(2):${in.bits.lsrc(2)} -> psrc(2):${out.bits.psrc(2)} " +
      p"ldest:${in.bits.ldest} -> pdest:${out.bits.pdest}\n"
    )
  }

  for ((x,y) <- io.in.zip(io.out)) {
    printRenameInfo(x, y)
  }

  val debugRedirect = RegEnable(io.redirect.bits, io.redirect.valid)
  // bad speculation
  val recStall = io.redirect.valid || io.robCommits.isWalk
  val ctrlRecStall = Mux(io.redirect.valid, io.redirect.bits.debugIsCtrl, io.robCommits.isWalk && debugRedirect.debugIsCtrl)
  val mvioRecStall = Mux(io.redirect.valid, io.redirect.bits.debugIsMemVio, io.robCommits.isWalk && debugRedirect.debugIsMemVio)
  val otherRecStall = recStall && !(ctrlRecStall || mvioRecStall)
  XSPerfAccumulate("recovery_stall", recStall)
  XSPerfAccumulate("control_recovery_stall", ctrlRecStall)
  XSPerfAccumulate("mem_violation_recovery_stall", mvioRecStall)
  XSPerfAccumulate("other_recovery_stall", otherRecStall)
  // freelist stall
  val notRecStall = !io.out.head.valid && !recStall
  val intFlStall = notRecStall && hasValid && !intFreeList.io.canAllocate
  val fpFlStall = notRecStall && hasValid && !fpFreeList.io.canAllocate
  // other stall
  val otherStall = notRecStall && !intFlStall && !fpFlStall

  io.stallReason.in.backReason.valid := io.stallReason.out.backReason.valid || !io.in.head.ready
  io.stallReason.in.backReason.bits := Mux(io.stallReason.out.backReason.valid, io.stallReason.out.backReason.bits,
    MuxCase(TopDownCounters.OtherCoreStall.id.U, Seq(
      ctrlRecStall  -> TopDownCounters.ControlRecoveryStall.id.U,
      mvioRecStall  -> TopDownCounters.MemVioRecoveryStall.id.U,
      otherRecStall -> TopDownCounters.OtherRecoveryStall.id.U,
      intFlStall    -> TopDownCounters.IntFlStall.id.U,
      fpFlStall     -> TopDownCounters.FpFlStall.id.U
    )
  ))
  io.stallReason.out.reason.zip(io.stallReason.in.reason).zip(io.in.map(_.valid)).foreach { case ((out, in), valid) =>
    out := Mux(io.stallReason.in.backReason.valid,
               io.stallReason.in.backReason.bits,
               Mux(valid, TopDownCounters.NoStall.id.U, in))
  }

  XSDebug(io.robCommits.isWalk, p"Walk Recovery Enabled\n")
  XSDebug(io.robCommits.isWalk, p"validVec:${Binary(io.robCommits.walkValid.asUInt)}\n")
  for (i <- 0 until CommitWidth) {
    val info = io.robCommits.info(i)
    XSDebug(io.robCommits.isWalk && io.robCommits.walkValid(i), p"[#$i walk info] pc:${Hexadecimal(info.pc)} " +
      p"ldest:${info.ldest} rfWen:${info.rfWen} fpWen:${info.fpWen} vecWen:${info.vecWen}")
  }

  XSDebug(p"inValidVec: ${Binary(Cat(io.in.map(_.valid)))}\n")

  XSPerfAccumulate("in", Mux(RegNext(io.in(0).ready), PopCount(io.in.map(_.valid)), 0.U))
  XSPerfAccumulate("utilization", PopCount(io.in.map(_.valid)))
  XSPerfAccumulate("waitInstr", PopCount((0 until RenameWidth).map(i => io.in(i).valid && !io.in(i).ready)))
  XSPerfAccumulate("stall_cycle_dispatch", hasValid && !io.out(0).ready && fpFreeList.io.canAllocate && intFreeList.io.canAllocate && !io.robCommits.isWalk)
  XSPerfAccumulate("stall_cycle_fp", hasValid && io.out(0).ready && !fpFreeList.io.canAllocate && intFreeList.io.canAllocate && !io.robCommits.isWalk)
  XSPerfAccumulate("stall_cycle_int", hasValid && io.out(0).ready && fpFreeList.io.canAllocate && !intFreeList.io.canAllocate && !io.robCommits.isWalk)
  XSPerfAccumulate("stall_cycle_walk", hasValid && io.out(0).ready && fpFreeList.io.canAllocate && intFreeList.io.canAllocate && io.robCommits.isWalk)

  XSPerfHistogram("slots_fire", PopCount(io.out.map(_.fire)), true.B, 0, RenameWidth+1, 1)
  // Explaination: when out(0) not fire, PopCount(valid) is not meaningfull
  XSPerfHistogram("slots_valid_pure", PopCount(io.in.map(_.valid)), io.out(0).fire, 0, RenameWidth+1, 1)
  XSPerfHistogram("slots_valid_rough", PopCount(io.in.map(_.valid)), true.B, 0, RenameWidth+1, 1)

  XSPerfAccumulate("move_instr_count", PopCount(io.out.map(out => out.fire && out.bits.isMove)))
  val is_fused_lui_load = io.out.map(o => o.fire && o.bits.fuType === FuType.ldu.U && o.bits.srcType(0) === SrcType.imm)
  XSPerfAccumulate("fused_lui_load_instr_count", PopCount(is_fused_lui_load))


  val renamePerf = Seq(
    ("rename_in                  ", PopCount(io.in.map(_.valid & io.in(0).ready ))                                                               ),
    ("rename_waitinstr           ", PopCount((0 until RenameWidth).map(i => io.in(i).valid && !io.in(i).ready))                                  ),
    ("rename_stall_cycle_dispatch", hasValid && !io.out(0).ready &&  fpFreeList.io.canAllocate &&  intFreeList.io.canAllocate && !io.robCommits.isWalk),
    ("rename_stall_cycle_fp      ", hasValid &&  io.out(0).ready && !fpFreeList.io.canAllocate &&  intFreeList.io.canAllocate && !io.robCommits.isWalk),
    ("rename_stall_cycle_int     ", hasValid &&  io.out(0).ready &&  fpFreeList.io.canAllocate && !intFreeList.io.canAllocate && !io.robCommits.isWalk),
    ("rename_stall_cycle_walk    ", hasValid &&  io.out(0).ready &&  fpFreeList.io.canAllocate &&  intFreeList.io.canAllocate &&  io.robCommits.isWalk)
  )
  val intFlPerf = intFreeList.getPerfEvents
  val fpFlPerf = fpFreeList.getPerfEvents
  val perfEvents = renamePerf ++ intFlPerf ++ fpFlPerf
  generatePerfEvent()
}
