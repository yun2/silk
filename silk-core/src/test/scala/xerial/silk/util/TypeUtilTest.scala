/*
 * Copyright 2012 Taro L. Saito
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xerial.silk.util

import _root_..
import java.io.File
import java.lang.reflect.Field

//--------------------------------------
//
// TypeUtilTest.scala
// Since: 2012/01/11 12:47
//
//--------------------------------------

/**
 * @author leo
 */
class TypeUtilTest extends SilkSpec {

  import TypeUtil._

  def conv[A <: Any](v: A): Unit = {
    val cl = v.getClass
    trace { """convert "%s" to %s""" format (v.toString, cl)}
    convert(v.toString, cl) must be (v)
  }
  def convAll[T <: Any](cl:Class[T], v: List[T]): Unit = {
    v.foreach (conv(_))
  }

  "TypeUtil" should "convert strings into AnyVals" in {

    conv(true)
    conv(34)
    conv("hello world")

    convAll(classOf[Int], List(1, 100, 2000, 342, -134, 43, 43))
    convAll(classOf[Boolean], List(true, false))
    convAll(classOf[Double], List(1.0, 1.43, 1.0e-10, 4, -43.34e-14))
    convAll(classOf[Float], List(1.0f, 1.43f, 1.0e-10f, 4f, 3.134f, -1234f, -34.34e-3f))
    convAll(classOf[Long], List(1L, 100L, 2000L, 342L, -134L, 43L, 43L))

  }

  "TypeUtil" should "convert Java primitive types" in {
    val m = ClassManifest.fromClass(java.lang.Boolean.TYPE)

    conv(java.lang.Boolean.TRUE)
    conv(java.lang.Boolean.FALSE)
    conv(new java.lang.Integer(1))
    
    conv(new java.lang.Long(3L))

  }

  "update field" should "set primitive type fields" in {
    
    class A {
      var i = 0
      var b = false
      var s = "hello"
      var f = 0.2f
      private var d = 0.01
      def getD = d
      var file:File = new File("sample.txt")
    }
    
    val a = new A
    def update(param:String, value:Any) {
      val f = a.getClass.getDeclaredField(param)
      updateField(a, f, value)
    }
    
    update("i", 10)
    update("b", "true")
    update("s", "hello world")
    update("f", 0.1234f)
    update("d", 0.134)
    update("file", "helloworld.txt")
    
    a.i must be (10)
    a.b must be (true)
    a.s must be ("hello world")
    a.f must be (0.1234f)
    a.getD must be (0.134)
    a.file.getName must be ("helloworld.txt")
    
  }
  
  
  "update field" should "increase the array size" in {

    class Sample {
      var input:Array[String] = Array.empty
      var num:Array[Int] = Array.empty
    }
    val a = new Sample
    val f = a.getClass.getDeclaredField("input")

    updateField(a, f, "hello")
    a.input.size must be (1)
    a.input(0) must be ("hello")

    updateField(a, f, "world")
    a.input.size must be (2)
    a.input(0) must be ("hello")
    a.input(1) must be ("world")

    val nf = a.getClass.getDeclaredField("num")
    updateField(a, nf, "1")
    updateField(a, nf, -10)
    updateField(a, nf, "-2")

    a.num.size must be (3)
    a.num(0) must be (1)
    a.num(1) must be (-10)
    a.num(2) must be (-2)


  }

  private def getField(obj:Any, name:String) : Field = {
    obj.getClass.getDeclaredField(name)
  }

  "update field" should "set enumeration values" in {

    object Fruit extends Enumeration {
      val Apple, Banana = Value
    }
    import Fruit._
    class E {
      var fruit = Apple
    }

    basicType(Apple.getClass) should be (BasicType.Enum)

    val e = new E
    updateField(e, getField(e, "fruit"), "Banana")
    e.fruit must be (Banana)

    updateField(e, getField(e, "fruit"), "apple") // Use lowercase
    e.fruit must be (Apple)
  }

  "update field" should "support Option[T]" in {
    class B {
      var opt : Option[String] = None
    }

    val b = new B
    val f = getField(b, "opt")
    isOption(f.getType) should be (true)
    updateField(b, f, "hello world")

    b.opt.isDefined must be (true)
    b.opt.get must be ("hello world")

    debug(b.opt)
  }

  "update field" should "support Option[Integer]" in {
    class C {
      // Option[Int] cannot be used since Int is a primitive type and will be erased
      // as Option<java.lang.Object>
      var num: Option[Integer] = None
    }
    val c = new C
    val f = getField(c, "num")
    updateField(c, f, "1345")

    //val t = getTypeParameters(f)
    //t(0) should be (classOf[Int])
    debug(c.num)

    c.num.isDefined must be (true)
    c.num.get must be (1345)
  }

  "update field" should "support Seq[_] type" in {

  }

}

