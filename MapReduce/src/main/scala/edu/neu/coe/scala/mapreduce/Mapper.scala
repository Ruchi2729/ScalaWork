package edu.neu.coe.scala.mapreduce

import akka.actor.{ Actor, ActorLogging, ActorRef }
import scala.collection.mutable.HashMap
import scala.util._

/**
 * The purpose of this mapper is to convert a sequence of V objects into several sequences, each of which is
 * associated with a key. It must be possible to do further processing (the reduce phase) on each of these
 * resulting sequences independently (and, thus in parallel).
 * Furthermore, the mapping function should try, when possible, to divide the input sequence into a number
 * of more or less equally lengthy sequences.
 * 
 * The mapper is an actor whose constructor takes a function f which converts a (K1,V1) into a (K2,V2).
 * The receive method recognizes an Incoming[K1,V1] as a message.
 * It replies with a Try[Map[K2,Seq[V2]]] which will be a Failure if any of the mappings fail.
 * 
 * Incoming is a convenience incoming message wrapper. It has the advantage of not suffering type erasure.
 * 
 * This mapper is strict in the sense that if there are any mapping exceptions, then the mapper as a whole fails
 * and returns an empty map (after logging an error).
 * 
 * @author scalaprof
 *
 * @param <K1> (input) key type (may be Unit)
 * @param <K2> (output) key type
 * @param <V1> (input) value type
 * @param <V2> (output) value type
 * 
 */
class Mapper[K1,V1,K2,V2](f: (K1,V1)=>(K2,V2)) extends Actor with ActorLogging {
  
  override def receive = {
    case  i: Incoming[K1,V1] =>
      log.info(s"received $i")
      log.debug(s"with map ${i.m}")
      val v2k2ts = for ((k1,v1) <- i.m) yield Try(f(k1,v1))
      sender ! prepareReply(v2k2ts)
    case z =>
      log.warning(s"received unknown message type: $z")
  }
  
  def prepareReply(v2k2ts: Seq[Try[(K2,V2)]]): Any = {
    Master.sequence(v2k2ts) match {
      case Success(v2k2s) => {
        val v2sK2m = HashMap[K2,Seq[V2]]() // mutable
        for ((k2,v2) <- v2k2s) v2sK2m put(k2, v2+:(v2sK2m get(k2) getOrElse(Nil)))
        Success(v2sK2m.toMap)
      }
      case f @ Failure(x) => f
    }
  }
  override def postStop = {
    log.debug("has shut down")
  }
  

}

/**
 * This sub-class of Mapper is more forgiving (and retains any exceptions thrown).
 * The reply is in the form of a tuple: (Map[K2,V2],Seq[Throwable])
 * 
 * @author scalaprof
 *
 * @param <K1>
 * @param <V1>
 * @param <K2>
 * @param <V2>
 */
class Mapper_Forgiving[K1,V1,K2,V2](f: (K1,V1)=>(K2,V2)) extends Mapper[K1,V1,K2,V2](f) {
  
  override def prepareReply(v2k2ts: Seq[Try[(K2,V2)]]) = {
      val v2sK2m = HashMap[K2,Seq[V2]]() // mutable
      val xs = Seq[Throwable]() // mutable
      for (v2k2t <- v2k2ts; v2k2e = Master.sequence(v2k2t))
        v2k2e match {
          case Right((k2,v2)) => v2sK2m put(k2, v2+:(v2sK2m get(k2) getOrElse(Nil)))
          case Left(x) => xs :+ x
      }
      (v2sK2m.toMap, xs.toSeq)
  }
}

case class Incoming[K, V](m: Seq[(K,V)]) {
  override def toString = s"Incoming: with ${m.size} elements"
}

object Incoming {
  def sequence[K,V](vs: Seq[V]): Incoming[K,V] = Incoming((vs zip Stream.continually(null.asInstanceOf[K])).map{_.swap})
  def map[K, V](vKm: Map[K,V]): Incoming[K,V] = Incoming(vKm.toSeq)
}

object Mapper {
}

