//--------------------------------------
//
// ClassBoxTest.scala
// Since: 2012/12/20 10:34 AM
//
//--------------------------------------

package xerial.silk.cluster

import xerial.silk.util.{ThreadUtil, SilkSpec}
import xerial.lens.TypeUtil

object ClassBoxTest {

  def thisClass = this.getClass

  def hello : String = { "hello" }
}

/**
 * @author Taro L. Saito
 */
class ClassBoxTest extends SilkSpec {

  import ClassBox._

  "ClassBox" should {
    "enumerate entries in classpath" in {
      val cb = ClassBox.current
      debug(s"sha1sum of classbox: ${cb.sha1sum}")
    }

    "create a classloder" in {
      val cb = ClassBox.current
      val loader = cb.isolatedClassLoader

      val h1 = ClassBoxTest.thisClass
      var h2 : Class[_] = null
      @volatile var mesg : String = null
      val t = ThreadUtil.newManager(1)
      t.submit {
        withClassLoader(loader) {
          try {
            h2 = loader.loadClass("xerial.silk.cluster.ClassBoxTest")
            val m = h2.getMethod("hello")
            mesg = TypeUtil.companionObject(h2) map { co =>  m.invoke(co).toString } getOrElse {
              warn(s"no companion object for $h2 is found")
              null
            }
          }
          catch {
            case e : Exception => warn(e)
          }
        }
      }
      t.join

      // Class loaded by different class loaders should have different IDs
      h1 should not be (h2)
      mesg should be ("hello")
    }

    "create local only ClassBox" in {
      val cb = ClassBox.localOnlyClassBox

      var mesg : String = null
      val loader = cb.isolatedClassLoader
      trace(s"${loader.getURLs.mkString(", ")}")
      withClassLoader(loader) {
        val h2 = loader.loadClass("xerial.silk.cluster.ClassBoxTest")
        val m = h2.getMethod("hello")
        mesg = m.invoke(null).asInstanceOf[String]
      }

      mesg shouldBe "hello"
    }
  }
}