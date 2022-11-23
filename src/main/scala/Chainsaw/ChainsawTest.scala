package Chainsaw

import Chainsaw.ChainsawTest._
import breeze.math.Complex
import spinal.core._
import spinal.core.sim._
import spinal.lib.{master, slave}
import spinal.sim._

import scala.collection.mutable.ArrayBuffer

// TODO: a richer testReport
case class ChainsawTestReport(passed: Boolean, input: Seq[Any], yours: Seq[Any], golden: Seq[Any])

/** auto test for a TransformModule
 *
 * @param testName name of the test, will be used as the directory name in simWorkspace
 * @param gen      dut generator
 * @param data     test vector
 * @param golden   golden vector, will be generated by impl of dut, if not provided
 */
case class ChainsawTest(
                         testName: String = "testTemp",
                         gen: ChainsawGenerator,
                         data: Seq[Any],
                         golden: Seq[Any] = null,
                         silentTest: Boolean = false
                       ) {

  def doTest(): ChainsawTestReport = {

    import gen._

    logger.info(
      s"\n----starting ChainsawTest on ${gen.name}----"
    )

    /** --------
     * format check
     * -------- */
    require(data.length % inputFormat.rawDataCount == 0, s"testing vector length ${data.length} is not a multiple of input frame raw data count ${gen.inputFormat.rawDataCount}")
    if (golden != null) require(golden.length % outputFormat.rawDataCount == 0, s"golden vector length ${golden.length} is not a multiple of output frame raw data count ${gen.outputFormat.rawDataCount}")
    if (golden != null) require(golden.length / outputFormat.rawDataCount == data.length / inputFormat.rawDataCount, "input/output frame numbers mismatch!")

    logger.info(s"testing vector length: ${data.length}, containing ${data.length / gen.inputFormat.rawDataCount} frames")
    val zero = getZero(data.head)

    /** --------
     * preparation for simulation
     * -------- */
    // constructing frames from raw data
    val raws = data.grouped(gen.inputFormat.rawDataCount).toSeq // frames of raw data
    if (raws.length < 2) logger.warn(s"your testcase contains one and only one frame")
    if (raws.length >= 2 && raws.head.equals(raws.last)) logger.warn(s"bad practice: frame0 & frame1 are exactly the same, they may cover some problems")

    val all = raws.map(inputFormat.fromRawData(_, zero)) // payload, valid & last
    val dataFlow = all.flatMap(_._1)
    val valid = all.flatMap(_._2)
    val last = all.flatMap(_._3)

    // data containers
    val dataRecord = ArrayBuffer[Seq[Any]]()
    val timeRecord = ArrayBuffer[Long]()

    val simTimeMax = (26 // forkStimulus & flushing
      + dataFlow.length // peek
      + gen.latency // wait for last valid
      + gen.outputFormat.period
      + actualOutTimes.max // wait for latest port
      ) * 2

    logger.info(
      s"\n----Chainsaw test status----" +
        s"\n\tmodules set as naive: \n\t\t${naiveSet.mkString("\n\t\t")}" +
        s"\n\tdata length = ${dataFlow.length} cycles, ${dataFlow.length / gen.inputFormat.period} frames in total"
    )


    /** --------
     * do simulation
     * -------- */
    SimConfig.workspaceName(testName).withFstWave.compile {
      /** --------
       * wrapper for time diff
       * -------- */
      new Module {

        val core = gen.getImplH
        val flowIn = slave(cloneOf(core.flowIn))
        val flowOut = master(cloneOf(core.flowOut))

        setDefinitionName(s"${gen.name}_dut")

        // compensation for unaligned inputs/outputs
        val outputSpan = actualOutTimes.max

        core.validIn := flowIn.valid
        core.lastIn := flowIn.last

        core.dataIn.zip(flowIn.fragment).zip(actualInTimes)
          .foreach { case ((corePort, dutPort), i) => corePort := dutPort.d(i) }
        flowOut.fragment.zip(core.dataOut).zip(actualOutTimes)
          .foreach { case ((dutPort, corePort), i) => dutPort := corePort.d(outputSpan - i) }

        flowOut.valid := core.validOut.validAfter(outputSpan)
        flowOut.last := core.lastOut.validAfter(outputSpan)
      }
    }.doSim { dut =>
      import dut.{clockDomain, flowIn, flowOut}

      // init
      def init(): Unit = {
        flowIn.valid #= false
        flowIn.last #= false
        clockDomain.forkStimulus(2)
        flowIn.fragment.foreach(_.randomize())
        clockDomain.waitSampling(9) // flushing
        flowIn.last #= true // refresh the inner state of dut
        clockDomain.waitSampling(1)
      }

      def poke(): SimThread = fork {
        var i = 0
        while (true) {
          if (i < dataFlow.length) {
            pokeWhatever(flowIn.fragment, dataFlow(i), gen.inputTypes)
            flowIn.valid #= valid(i)
            flowIn.last #= last(i)
          } else {
            pokeWhatever(flowIn.fragment, flowIn.fragment.map(_ => zero), gen.inputTypes)
            flowIn.valid #= false
            flowIn.last #= false
          }
          i += 1
          clockDomain.waitSampling()
        }
      }

      def peek(): SimThread = fork {
        while (true) {
          if (flowOut.valid.toBoolean) {
            dataRecord += peekWhatever(flowOut.fragment, gen.outputTypes).asInstanceOf[Seq[Any]]
            timeRecord += simTime()
          }
          clockDomain.waitSampling()
        }
      }

      def waitSimDone(): Any = {
        do {
          clockDomain.waitSampling(10)
        } while (simTime() <= simTimeMax)
      }

      init()
      peek()
      poke()
      waitSimDone() // working on the main thread, pushing the simulation forward
    }

    /** --------
     * analysis after simulation
     * -------- */

    val yours: Seq[Seq[Any]] = implMode match {
      case Comb =>
        dataRecord
          .grouped(outputFormat.period).toSeq // frames
          .map(outputFormat.toRawData) // frames -> raw data
      case Infinite =>
        logger.info(s"offset = $offset")
        Seq(dataRecord.flatten.drop(gen.offset))
    }

    val goldenInUse: Seq[Seq[Any]] =
      if (golden == null) implMode match {
        case Comb =>
          val ret = raws.map(impl)
          require(ret.head.length == outputFormat.rawDataCount,
            s"golden model format mismatch, expected: ${outputFormat.rawDataCount}, actual:${ret.head.length}")
          ret
        case Infinite => Seq(impl(data))
      }
      else golden.grouped(outputFormat.rawDataCount).toSeq

    val success = {
      // get and compare golden & yours slice by slice
      // compare yours with the golden frame by frame until the first mismatch
      val remained = yours.zip(goldenInUse).zipWithIndex.dropWhile { case ((y, g), _) => metric.frameWise(y, g) }
      if (remained.nonEmpty && !silentTest) {
        val ((y, g), i) = remained.head
        showErrorMap(gen, raws(i), y, g, i, metric)
      }
      remained.isEmpty
    }

    if (!silentTest) {

      if (success) implMode match {
        case Comb => logger.info(s"test for generator ${gen.name} passed\n${showAllData(gen, raws.last, yours.last, goldenInUse.last, raws.length)}")
        case StateMachine =>
        case Infinite => logger.info(s"test for generator ${gen.name} passed\n${showInfiniteData(raws.flatten, yours.flatten, goldenInUse.flatten)}")
      }
      else logger.error(s"test for generator ${gen.name} failed")

      assert(success)
    }

    ChainsawTestReport(success, raws, yours, goldenInUse)
  }
}

object ChainsawTest {

  /** --------
   * methods for numeric types conversion
   * -------- */
  def getZero[T](data: T): T = {
    val temp = data match {
      case _: Bool => false
      case _: BigInt => BigInt(0)
      case _: Double => 0.0
      case _: Complex => Complex(0, 0)
    }
    temp.asInstanceOf[T]
  }

  def pokeWhatever(port: Vec[Bits], data: Seq[Any], typeInfos: Seq[NumericType]): Unit =
    port.zip(typeInfos).zip(data).foreach { case ((bits, info), v) => bits #= info.toBigInt(v) }

  def peekWhatever(port: Vec[Bits], typeInfos: Seq[NumericType]) =
    port.zip(typeInfos).map { case (bits, info) => info.fromBigInt(bits.toBigInt) }

  /** --------
   * methods for debug/visualization
   * -------- */
  def showData[T](data: Seq[T], portSize: Int) = {
    val elementsPerCycle = 8 max data.length / portSize // TODO: this should be adjustable
    val cycles = 4 // TODO: this should be adjustable

    def showRow(data: Seq[T]) = data.take(elementsPerCycle).mkString(" ") +
      (if (data.length > elementsPerCycle) s" ${data.length - elementsPerCycle} more elements... " else "")

    val matrix = data.grouped(portSize).toSeq
    matrix.take(cycles).map(showRow).mkString("\n") +
      (if (matrix.length > 2 * cycles) s"\n${matrix.length - 2 * cycles} more cycles... \n" else "") +
      (if (matrix.length >= 2 * cycles) "\n" + matrix.takeRight(cycles).map(showRow).mkString("\n") else "")
  }

  def showAllData[T](gen: ChainsawGenerator, input: Seq[T], yours: Seq[T], golden: Seq[T], index: Int): String =
    s"\n$index-th frame:" +
      s"\ninput :\n${showData(input, gen.sizeIn)}" +
      s"\nyours :\n${showData(yours, gen.sizeOut)}" +
      s"\ngolden:\n${showData(golden, gen.sizeOut)}"

  def showErrorMap[T](gen: ChainsawGenerator, inputs: Seq[T], y: Seq[T], g: Seq[T], i: Int, metric: ChainsawMetric): Unit = {
    // show the shape of errors
    val errors = y.zip(g).map { case (eleY, eleG) => metric.elementWise(eleY, eleG) }
    val errorMap = gen.outputFormat.fromRawData(errors, true)._1
      .zipWithIndex.map { case (seq, i) => s"c$i".padTo(5, ' ') + seq.map(ele => if (ele) " " else "■").mkString("") }
      .mkString("\n")

    logger.info(
      s"\n----error frame report----" +
        s"\nelementWise errors:\n$errorMap" +
        s"\n${showAllData(gen, inputs, y, g, i)}"
    )
  }

  def showInfiniteData[T](input: Seq[T], yours: Seq[T], golden: Seq[T]) = {
    s"\ninput :\n${input.mkString(" ")}" +
      s"\nyours :\n${yours.mkString(" ")}" +
      s"\ngolden:\n${golden.mkString(" ")}"
  }
}