//--------------------------------------
//
// WorkflowTracer.scala
// Since: 2013/04/16 3:53 PM
//
//--------------------------------------

package xerial.silk.core

import xerial.silk.core.SilkWorkflow._
import org.objectweb.asm._
import tree.analysis.{BasicValue, Analyzer, SimpleVerifier}
import tree.{MethodInsnNode, MethodNode}
import xerial.silk.cluster.{LazyF0, ClosureSerializer}
import xerial.core.log.Logger
import java.lang.reflect.{Modifier, Method}
import xerial.lens.TypeUtil
import scala.language.existentials
import xerial.silk.core.SilkWorkflow.FlowMap
import xerial.silk.core.SilkWorkflow.ShellCommand
import scala.Some
import xerial.silk.core.SilkWorkflow.CommandSeq

trait FunctionRef {
  def name : String
}
case class Function2Ref[A, B](name:String, f:A=>B) extends FunctionRef
case class MethodRef(cl:Class[_], opcode:Int, flags:Int, name:String, desc:String, stack:IndexedSeq[String]) extends FunctionRef {
  override def toString = s"MethodRef(cl:${cl.getName}, name:${name}, desc:${desc}, stack:[${stack.mkString(", ")}])"
  def toReportString = s"MethodCall[$opcode]:${name}${desc}\n -owner:${cl.getName}${if(stack.isEmpty) "" else "\n -stack:\n  -" + stack.mkString("\n  -")}"

}

case class SilkDependency(ref:FunctionRef, method:IndexedSeq[MethodRef]) {
  override def toString = s"${ref.name} =>\n${method.map(_.toString).mkString("\n")}}"
}


/**
 *
 * @author Taro L. Saito
 */
object WorkflowTracer extends Logger {

  import ClosureSerializer._

  /**
   * Extract method that matches the given name and returns SilkSingle or Silk type data
   * @param name
   * @param argLen
   * @return
   */
  private def methodFilter(name:String, argLen:Int) : MethodRef => Boolean = { m : MethodRef =>
    def isTarget : Boolean = m.name == name && Type.getArgumentTypes(m.desc).length == argLen
    def isSilkFlow : Boolean = {
      val rt = Class.forName(Type.getReturnType(m.desc).getClassName, false, Thread.currentThread.getContextClassLoader)
      debug(s"return type: $rt")
      isSilkType(rt)
    }
    isTarget && isSilkFlow
  }

  private def isSilkType[A](cl:Class[A]) : Boolean = {
    return classOf[Silk[_]].isAssignableFrom(cl)
  }


  def traceSilkFlow[A, B](name:String, silk:SilkFlow[A, B]) : Option[SilkDependency] = {

    def findFromArg[T](fName:String, arg:T) : Seq[SilkDependency] = {
      val cl = arg.getClass
      if(!isSilkType(cl))
        return Seq.empty

      debug(s"arg: $arg, type:${arg.getClass}")
      find(fName, arg.asInstanceOf[Silk[_]])
    }

    def find[P](fName:String, current:Silk[P]) : Seq[SilkDependency] = {
      trace(s"find dependency in ${current.getClass.getName}")
      current match {
        case CommandSeq(prev, next) =>
          find(fName, prev) ++ find(fName, next)
        case sc : ShellCommand =>
          sc.argSeq.flatMap(arg => findFromArg(fName, arg))
        case FlowMap(prev, f) =>
          find(fName, prev) ++ traceSilkFlow(f)
        case RootWrap(name, in) =>
          find(fName, in)
        case f:SilkFile => Seq.empty
        case _ =>
          warn(s"unknown flow type: $current")
          Seq.empty
      }
    }

    debug(s"traceSilkFlow name:$name, $silk")

    find(name, silk)
    None
  }




  def traceMethodFlow[A](cl:Class[A], methodName:String) : Option[SilkDependency] = {
    cl.getDeclaredMethods.find(m => m.getName == methodName && isSilkType(m.getReturnType)).flatMap { m : Method =>
      val mc = findMethodCall(cl, methodFilter(m.getName, m.getParameterTypes.length))
      val filtered = filterByContextClasses(cl, mc)
      if(filtered.isEmpty)
        None
      else
        Some(SilkDependency(MethodRef(cl, -1, 0, methodName, "", IndexedSeq.empty), filtered.toIndexedSeq))
    }
  }

  private def resolveMethodCalledByFunctionRef[A](funcClass:Class[A]) : Option[MethodRef] = {
    def isSynthetic(flags:Int) = (flags & 0x1000) != 0

    val mc = findMethodCall(funcClass, {m :MethodRef =>
      m.name == "apply" && !isSynthetic(m.flags)
    })
    mc.headOption
  }

  def traceSilkFlow[R](f0: => R) : Option[SilkDependency] = {
    val lazyf0 = LazyF0(f0)
    val funcClass = lazyf0.functionClass
    val m = resolveMethodCalledByFunctionRef(funcClass)
    m.flatMap{mc =>
      val eval = f0
      if(classOf[SilkFlow[_, _]].isAssignableFrom(eval.getClass))
        traceSilkFlow(mc.name, eval.asInstanceOf[SilkFlow[_, _]])
      else
        traceMethodFlow(mc.cl, mc.name)
    }
  }

  /**
   * Trace silk flow dependencies appearing inside the function
   * @param f
   * @tparam P
   * @tparam Q
   */
  def traceSilkFlow[P, Q](f:P => Q) : Option[SilkDependency] = {
    val m = resolveMethodCalledByFunctionRef(f.getClass)
    m.flatMap{mc => traceMethodFlow(mc.cl, mc.name) }
  }


  def findMethodCall[A](cl:Class[A], methodFilter:MethodRef => Boolean) : Seq[MethodRef] = {
    def getClassReader(cl: Class[_]): ClassReader = {
      new ClassReader(cl.getResourceAsStream(
        cl.getName.replaceFirst("^.*\\.", "") + ".class"))
    }
    val visitor = new ClassTracer(cl, methodFilter)
    getClassReader(cl).accept(visitor, 0)
    visitor.found.toSeq
  }

  /**
   * Extract method calls within the scope of a given class
   * @param cl
   * @param methodCall
   * @tparam A
   * @return
   */
  def filterByContextClasses[A](cl:Class[A], methodCall:Seq[MethodRef]) : Seq[MethodRef] = {
    val contextClasses = {
      val parents =
        for(p <- Seq(cl.getSuperclass) ++ cl.getInterfaces
            if !p.getName.startsWith("scala.") && !p.getName.startsWith("java.")) yield p.getName

      parents.toSet + cl.getName + "xerial.silk.core"
    }
    def isTargetClass(clName:String) : Boolean =
      contextClasses.exists(c => clName.startsWith(c))

    def isTarget(m:MethodRef) : Boolean = {
      val argTypes = Type.getArgumentTypes(m.desc)
      val argLength = argTypes.length
      m.opcode match {
        case Opcodes.INVOKESTATIC =>
          // v1, v2, ...
          val args = m.stack.takeRight(argLength)
          args exists isTargetClass
        case _ =>
          // o, v1, v2, ...
          val args = m.stack.takeRight(argLength+1)
          args exists isTargetClass
      }
    }

    methodCall filter isTarget
  }




  import ClosureSerializer.MethodCall

  class ClassTracer[A](cl:Class[A], cond: MethodRef => Boolean) extends ClassVisitor(Opcodes.ASM4) {
    val owner = cl.getName
    var found = Set[MethodRef]()

    trace(s"Find method call in $owner")

    override def visitMethod(access: Int, name: String, desc: String, signature: String, exceptions: Array[String]) = {
      if(cond(MethodRef(cl, -1, access, name, desc, IndexedSeq.empty)))
        new MethodTracer(access, name, desc, signature, exceptions)
      else
        null
    }

    class MethodTracer(access:Int, name:String, desc:String, signature:String, exceptions:Array[String])
      extends MethodVisitor(Opcodes.ASM4, new MethodNode(Opcodes.ASM4, access, name, desc, signature, exceptions)) {
      override def visitEnd() {
        super.visitEnd()
        debug(s"analyze method ${name}${desc}")
        try {
          val a = new Analyzer(new SimpleVerifier)
          val mn = mv.asInstanceOf[MethodNode]
          a.analyze(owner, mn)
          val inst = for(i <- 0 until mn.instructions.size()) yield mn.instructions.get(i)
          for((f, m:MethodInsnNode) <- a.getFrames.zip(inst) if f != null) {
            val stack = (for (i <- 0 until f.getStackSize) yield f.getStack(i).asInstanceOf[BasicValue].getType.getClassName).toIndexedSeq
            val ownerName = clName(m.owner)
            val methodOwner = Class.forName(ownerName, false, Thread.currentThread.getContextClassLoader)
            val ref = MethodRef(methodOwner, m.getOpcode, access, m.name, m.desc, stack)
            trace(s"Found ${ref.toReportString}")
            // Search also anonymous functions defined in the target class
            found += ref
          }
        }
        catch {
          case e:Exception => warn(e)
        }
      }
    }

  }






}