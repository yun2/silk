//--------------------------------------
//
// SilkFrameworkTest.scala
// Since: 2013/06/09 12:15
//
//--------------------------------------

package xerial.silk.framework

import xerial.silk.util.SilkSpec



trait RunLogger extends InMemoryFramework {

  abstract override def run[A](silk: Silk[A]) : Result[A] = {
    debug(s"run $silk")
    val result = super.run(silk)
    debug(s"result: $result")
    result
  }
}


class TestFramework extends InMemoryRunner with RunLogger
class SliceFramework
  extends InMemorySliceEvaluator
  with InMemorySliceStorage
  with DefaultStageManager
  with RunLogger {


  override def evaluator = new EvaluatorImpl {
    override def getSlices[A](v: Silk[A]) = {
      debug(s"getSlices $v")
      val result = super.getSlices(v)
      debug(s"result: $result")
      result
    }
  }
}

/**
 * @author Taro L. Saito
 */
class SilkFrameworkTest extends SilkSpec {
  "SilkFramework" should {

    "have in-memory runner" in {
      val f = new TestFramework
      val in = f.newSilk(Seq(1, 2, 3, 4, 5, 6))
      val op = in.map(_ * 2).filter(_ < 10).reduce(_ + _)
      val result = f.run(op)
      result shouldBe Seq(20)
    }

    "have Silk splitter" in {
      val f = new SliceFramework
      val in = f.newSilk(Seq(1, 2, 3, 4, 5, 6))
      val op = in.map(_ * 2).filter(_ < 10).reduce(_ + _)
      val result = f.run(op)
    }

  }
}