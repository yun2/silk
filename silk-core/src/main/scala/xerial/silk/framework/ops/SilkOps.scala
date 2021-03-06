//--------------------------------------
//
// SilkOps.scala
// Since: 2013/06/16 16:13
//
//--------------------------------------

package xerial.silk.framework.ops

import scala.language.existentials
import scala.language.experimental.macros
import scala.reflect.ClassTag
import xerial.lens.{Parameter, ObjectSchema}
import scala.reflect.runtime.{universe=>ru}
import java.io._
import xerial.silk.SilkException._
import xerial.core.io.text.UString
import java.util.UUID
import xerial.silk.core.ClosureSerializer
import xerial.silk._

/**
 * This file defines Silk operations
 */


case class LoadFile(id:UUID, fc:FContext, file:File) extends SilkSingle[File] {
  def lines : SilkSeq[String] = NA
  def rawLines : SilkSeq[UString] = NA
  def as[A](implicit ev:ClassTag[A]) : SilkSeq[A] = NA
}

trait HasInput[A] {
  self:Silk[_] =>
  val in : SilkSeq[A]

  override def inputs  = Seq(in)
}

trait HasSingleInput[A] {
  self:Silk[_] =>
  val in : SilkSingle[A]

  override def inputs  = Seq(in)
}

case class FilterOp[A: ClassTag](id:UUID, fc: FContext, in: SilkSeq[A], f: A => Boolean, @transient fe: ru.Expr[A => Boolean])
  extends SilkSeq[A] with HasInput[A]

case class FlatMapOp[A, B](id:UUID, fc: FContext, in: SilkSeq[A], f: A => SilkSeq[B], @transient fe: ru.Expr[A => SilkSeq[B]])
  extends SilkSeq[B]
{
  override def inputs = Seq(in)
  def fwrap = f.asInstanceOf[Any => SilkSeq[Any]]
}

case class MapOp[A, B](id:UUID, fc: FContext, in: SilkSeq[A], f: A => B, @transient fe: ru.Expr[A => B])
  extends SilkSeq[B] with HasInput[A]
{
  def clean = MapOp(id, fc, in, ClosureSerializer.cleanupF1(f), fe)
  def fwrap = f.asInstanceOf[Any => Any]
}

case class ForeachOp[A, B: ClassTag](id:UUID, fc: FContext, in: SilkSeq[A], f: A => B, @transient fe: ru.Expr[A => B])
  extends SilkSeq[B] with HasInput[A]
{
  def fwrap = f.asInstanceOf[Any => Any]
}

case class GroupByOp[A, K](id:UUID, fc: FContext, in: SilkSeq[A], f: A => K, @transient fe: ru.Expr[A => K])
  extends SilkSeq[(K, SilkSeq[A])] with HasInput[A]
{
  def fwrap = f.asInstanceOf[Any => Any]
}

case class SamplingOp[A](id:UUID, fc:FContext, in:SilkSeq[A], proportion:Double)
 extends SilkSeq[A] with HasInput[A]


case class RawSeq[+A: ClassTag](id:UUID, fc: FContext, @transient in:Seq[A])
  extends SilkSeq[A]


case class SizeOp[A](id:UUID, fc:FContext, in:SilkSeq[A]) extends SilkSingle[Long] with HasInput[A] {

}

case class ShuffleOp[A, K](id:UUID, fc: FContext, in: SilkSeq[A], partitioner: Partitioner[A])
  extends SilkSeq[A] with HasInput[A]

case class ShuffleReduceOp[A](id:UUID, fc: FContext, in: ShuffleOp[A, _], ord:Ordering[A])
  extends SilkSeq[A] with HasInput[A]

case class MergeShuffleOp[A: ClassTag, B: ClassTag](id:UUID, fc: FContext, left: SilkSeq[A], right: SilkSeq[B])
  extends SilkSeq[(A, B)] {
  override def inputs = Seq(left, right)
}

case class NaturalJoinOp[A: ClassTag, B: ClassTag](id:UUID, fc: FContext, left: SilkSeq[A], right: SilkSeq[B])
  extends SilkSeq[(A, B)] {
  override def inputs = Seq(left, right)

  def keyParameterPairs = {
    val lt = ObjectSchema.of[A]
    val rt = ObjectSchema.of[B]
    val lp = lt.constructor.params
    val rp = rt.constructor.params
    for (pl <- lp; pr <- rp if (pl.name == pr.name) && pl.valueType == pr.valueType) yield (pl, pr)
  }
}
case class JoinOp[A, B, K](id:UUID, fc:FContext, left:SilkSeq[A], right:SilkSeq[B], k1:A=>K, k2:B=>K) extends SilkSeq[(A, B)] {
  override def inputs = Seq(left, right)
}
//case class JoinByOp[A, B](id:UUID, fc:FContext, left:SilkSeq[A], right:SilkSeq[B], cond:(A, B)=>Boolean) extends SilkSeq[(A, B)]

case class ZipOp[A, B](id:UUID, fc:FContext, left:SilkSeq[A], right:SilkSeq[B])
  extends SilkSeq[(A, B)] {
  override def inputs = Seq(left, right)
}

case class MkStringOp[A](id:UUID, fc:FContext, in:SilkSeq[A], start:String, sep:String, end:String)
  extends SilkSingle[String] with HasInput[A]

case class ZipWithIndexOp[A](id:UUID, fc:FContext, in:SilkSeq[A])
  extends SilkSeq[(A, Int)] with HasInput[A]

case class NumericFold[A](id:UUID, fc:FContext, in:SilkSeq[A], z: A, op: (A, A) => A) extends SilkSingle[A] with HasInput[A]
case class NumericReduce[A](id:UUID, fc:FContext, in:SilkSeq[A], op: (A, A) => A) extends SilkSingle[A] with HasInput[A]

case class SortByOp[A, K](id:UUID, fc:FContext, in:SilkSeq[A], keyExtractor:A=>K, ordering:Ordering[K])
  extends SilkSeq[A] with HasInput[A]

case class SortOp[A](id:UUID, fc:FContext, in:SilkSeq[A], ordering:Ordering[A], partitioner:Partitioner[A])
  extends SilkSeq[A] with HasInput[A]


case class SplitOp[A](id:UUID, fc:FContext, in:SilkSeq[A])
  extends SilkSeq[SilkSeq[A]]  with HasInput[A]


case class ConcatOp[A, B](id:UUID, fc:FContext, in:SilkSeq[A], asSilkSeq:A=>SilkSeq[B])
  extends SilkSeq[B]  with HasInput[A]


case class MapSingleOp[A, B : ClassTag](id:UUID, fc: FContext, in:SilkSingle[A], f: A=>B, @transient fe: ru.Expr[A=>B])
  extends SilkSingle[B]  with HasSingleInput[A]


case class FilterSingleOp[A: ClassTag](id:UUID, fc: FContext, in:SilkSingle[A], f: A=>Boolean, @transient fe: ru.Expr[A=>Boolean])
  extends SilkSingle[A]  with HasSingleInput[A]



case class SilkEmpty(id:UUID, fc:FContext) extends SilkSingle[Nothing] {
  override def size = 0

}
case class ReduceOp[A: ClassTag](id:UUID, fc: FContext, in: SilkSeq[A], f: (A, A) => A, @transient fe: ru.Expr[(A, A) => A])
  extends SilkSingle[A]  with HasInput[A] {
  override def inputs = Seq(in)
}



