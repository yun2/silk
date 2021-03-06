//--------------------------------------
//
// SilkFlowCallGraphallGraph.scala
// Since: 2013/05/10 4:14 PM
//
//--------------------------------------

package xerial.silk.core

import xerial.core.log.Logger
import xerial.lens.TypeUtil
import scala.reflect.runtime.{universe=>ru}
import ru._
import scala.math.Ordered.orderingToOrdered
import scala.language.existentials
import xerial.silk.framework.ops._
import xerial.silk.Silk

/**
 * @author Taro L. Saito
 */
object SilkFlowCallGraph extends Logger {

  private[silk] val mirror = ru.runtimeMirror(Thread.currentThread.getContextClassLoader)
  import scala.tools.reflect.ToolBox
  private[silk] val toolbox = mirror.mkToolBox()

  def apply[A](dataflow:Any) : SilkFlowCallGraph = {
    val b = new Builder
    b.traverseParent(None, None, dataflow)
    b.build
  }


  def isSilkType[A](cl:Class[A]) : Boolean = classOf[Silk[_]].isAssignableFrom(cl)
  def isSilkTypeSymbol(s:ru.Symbol) : Boolean = {
    val tc : ru.Tree = toolbox.typeCheck(Ident(s))
    debug(s"type checked: ${tc.tpe}")
    isSilkType(mirror.runtimeClass(tc.tpe))
  }

  case class Context(boundVariable:Set[String] = Set.empty, freeVariable:Set[RefNode[_]]=Set.empty) {
  }

  private class Builder[A] {

    var visited = Set.empty[(Context, Any)]



    val g = new SilkFlowCallGraph

    private def findValDefs(fExpr:ru.Expr[_]) : List[ValDef] = {
      fExpr match {
        case Expr(Function(valDef, body)) =>
          valDef map { case v @ValDef(mod, name, a1, a2) => v }
        case _ => List.empty
      }
    }

    def traverseParent(parentNode:Option[DataFlowNode], childNode:Option[DataFlowNode], a:Any)
     = traverse(parentNode, childNode, Context(), a, isForward=false)


    def traverse(parentNode:Option[DataFlowNode], childNode:Option[DataFlowNode], context:Context, a:Any, isForward:Boolean = true) {

     val t = (context, a)
     if(visited.contains(t))
       return

      visited += t

      def newNode(n:DataFlowNode) = {
        g.add(n)
        for(p <- parentNode)
          g.connect(p, n)
        for(c <- childNode)
          g.connect(n, c)
        n
      }


      def zero[A](cl:Class[A]) = cl match {
        case f if isSilkType(f) =>
          Silk.empty
        case _ => TypeUtil.zero(cl)
      }


      def traverseMap[A,B](sf:Silk[_], prev:Silk[A], f:A=>B, fExpr:ru.Expr[_]) {
        val vd = findValDefs(fExpr)
        val boundVariables : Set[String] = context.boundVariable ++ vd.map(_.name.decoded)
        val n = newNode(FNode(sf, vd))

        // Traverse input node
        traverseParent(None, Some(n), prev)

        // Add dependency to free variable
        for(fv <- context.freeVariable) {
          g.connect(fv, n)
        }

        // Extract free variables
        val freeVariableNodes : Seq[RefNode[_]] = for(fv <- fExpr.tree.freeTerms if isSilkTypeSymbol(fv)) yield {
          trace(s"find silk ref: fv ${fv}")
          val v = toolbox.eval(Ident(fv))
          val r = RefNode(v.asInstanceOf[Silk[_]], fv.name.decoded, v.getClass)
          g.add(r)
          //g.connect(r, n)
          traverseParent(None, Some(r), v)
          r
        }


        val freeVariables = context.freeVariable ++ freeVariableNodes
        //debug(s"fExpr:${showRaw(fExpr)}, free term: ${freeVarablesInFExpr}")

        fExpr.staticType match {
          case t @ TypeRef(prefix, symbol, List(from, to)) =>
            if(isSilkType(mirror.runtimeClass(to))) {
              // Run the function to obtain its result by using a dummy input
              val inputCl = mirror.runtimeClass(from)
              val z = zero(inputCl)
              val nextExpr = f.asInstanceOf[Any => Any].apply(z)
              // Replace the dummy input
              val ne = nextExpr match {
//                case f:WithInput[_] if f.prev.isRaw =>
//                  f.copyWithoutInput
//                case f:WithInput[_] if !f.prev.isRaw =>
//                  debug(s"here: $nextExpr")
//                  nextExpr
                case _ =>
                  nextExpr
              }

              traverse(Some(n), None, Context(boundVariables, freeVariables), ne)
            }
          case other => warn(s"unknown type: ${other}")
        }

      }


      def traverseCmdArg(c:DataFlowNode, e:ru.Expr[_]) {
        trace(s"traverse cmd arg: ${showRaw(e)}")

        def traceType(st:ru.Type, cls:Option[MethodOwnerRef], term:ru.Name) {
          val rc = mirror.runtimeClass(st)
          if(isSilkType(rc)) {
            val ref = toolbox.eval(e.tree)
            val rn = RefNode(ref.asInstanceOf[Silk[_]], term.decoded, rc)
            g.connect(rn, c) // rn is referenced in the context
            traverseParent(None, Some(rn), ref)
          }
        }

        e match {
          case Expr(i @ Ident(term)) =>
            traceType(e.staticType, None, term)
          case Expr(Select(cls, term)) =>
            traceType(e.staticType, resolveClass(cls), term)
          case _ => warn(s"unknown expr type: ${showRaw(e)}")
        }
      }


      trace(s"visited $a")

      a match {
        case fm @ FlatMapOp(id, fc, prev, f, fExpr) =>
          traverseMap(fm, prev, f, fExpr)
        case mf @ MapOp(id, fc, prev, f, fExpr) =>
          traverseMap(mf, prev, f, fExpr)
        case mf @ ForeachOp(id, fc, prev, f, fExpr) =>
          traverseMap(mf, prev, f, fExpr)
        case mf @ FilterOp(id, fc, prev, f, fExpr) =>
          traverseMap(mf, prev, f, fExpr)
        case s @ CommandOp(id, fc, sc, args, argExpr) =>
          val n = g.add(DNode(s))
          newNode(n)
          argExpr.foreach(traverseCmdArg(n, _))
//        case cs @ CommandSeq(prev, next) =>
//          val n = newNode(DNode(cs))
//          traverseParent(None, Some(n), prev)
//          traverse(Some(DNode(prev)), None, context, next)
        case j @ JoinOp(id, fc, l, r, k1, k2) =>
          val n = newNode(DNode(j))
          traverseParent(None, Some(n), l)
          traverse(None, Some(n), Context(), r)
        case z @ ZipOp(id, fc,p, o) =>
          val n = newNode(DNode(z))
          traverseParent(None, Some(n), p)
          traverseParent(None, Some(n), o)
//        case c @ LineInput(cmd) =>
//          val n = newNode(DNode(c))
//          traverseParent(None, Some(n), cmd)
//        case r @ RawInput(in) =>
//          val n = newNode(DNode(r))
//        case s @ RawInputSingle(e) =>
//          val n = newNode(DNode(s))
//        case w: WithInput[_] =>
//          val n = newNode(DNode(w.asInstanceOf[Silk[_]]))
//          traverseParent(None, Some(n), w.prev)
//        case f:SilkFlow[_, _] =>
//          warn(s"not yet implemented ${f.getClass.getSimpleName}")
        // TODO add other cases
        case e =>
          // ignore
      }
    }

    def resolveClass(t:ru.Tree) : Option[MethodOwnerRef] = {
      t match {
        case This(typeName) => Some(ThisTypeRef(typeName.decoded))
        case Ident(refName) => Some(IdentRef(refName.decoded))
        case _ => None
      }
    }


    def build : SilkFlowCallGraph = g

  }

}

trait DataFlowNode {
  def flow : Silk[_]
}
case class FNode[A](flow:Silk[A], valDefs:List[ValDef]) extends DataFlowNode {
  override def toString = {
    val s = new StringBuilder
    s.append(s"f(${valDefs.map(v => v.name.decoded).mkString(", ")}) =>\n${flow}")
    s.result
  }
}
case class DNode[A](flow:Silk[A]) extends DataFlowNode {
  override def toString = flow.toString
}

case class RefNode[_](flow:Silk[_], name:String, targetType:Class[_]) extends DataFlowNode {
}



class SilkFlowCallGraph() extends Logger {

  private var nodeCount = 0

  private var nodeTable = IndexedSeq[DataFlowNode]()
  private var nodeIDIndex = Map[DataFlowNode, Int]()
  private var edges = Set[(Int, Int)]()

  def id(n:DataFlowNode) = nodeIDIndex.getOrElse(n, -1)

  def apply(id:Int) : DataFlowNode = nodeTable(id-1)

  def nodeIDs = for(i <- 1 to nodeTable.size) yield i

  def rootNodeIDs = {
    val nodesWithIncomingEdges = for((s, d) <- edges) yield d
    nodeIDs.toSet -- nodesWithIncomingEdges
  }

  def destOf(id:Int) = edges.collect{case (from, to) if from == id => to}
  def inputOf(id:Int) = edges.collect{case (from, to) if to == id => from}
  def inputNodeOf(id:Int) = edges.collect{case (from, to) if to == id => apply(from)}

  override def toString = {
    val b = new StringBuilder
    b.append("[nodes]\n")
    for(n <- nodeTable) {
      b.append(f"[${id(n)}]: ${n}\n")
    }
    b.append("[edges]\n")

    def print(v:DataFlowNode) = {
      v match {
        case FNode(flow, vd) if vd.size == 1 =>
          s"${id(v)}(${vd.head.name.decoded})"
        case RefNode(_, name, _) =>
          s"${name}:${id(v)}"
        case _ => s"${id(v)}"
      }
    }

    for((f, t) <- edges.toSeq.sortBy{ case (a:Int, b:Int) => (b, a)}) {
      b.append(s"${print(this(f))} -> ${print(this(t))}\n")
    }
    b.result
  }

  def add(n:DataFlowNode) : DataFlowNode = {
    if(!nodeTable.contains(n)) {
      val newID = nodeCount + 1
      nodeCount += 1
      nodeTable :+= n
      nodeIDIndex += n -> newID
      trace(s"Add node: [$newID]:$n")
    }
    n
  }

  def connect(from:DataFlowNode, to:DataFlowNode) {
    add(from)
    add(to)
    trace(s"connect ${id(from)} -> ${id(to)}")

    edges += id(from) -> id(to)
  }

}


