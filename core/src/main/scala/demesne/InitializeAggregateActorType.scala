package demesne

import akka.Done

import scalaz._
import Scalaz._
import scala.concurrent.{ExecutionContext, Future}
import akka.util.Timeout
import peds.commons.Valid



trait InitializeAggregateActorType { 
  def initialize( props: Map[Symbol, Any] )( implicit ec: ExecutionContext, to: Timeout ): Valid[Future[Done]]
}

object InitializeAggregateActorType {
  def apply(
    props: Map[Symbol, Any]
  )(
    types: InitializeAggregateActorType*
  )(
    implicit ec: ExecutionContext,
    to: Timeout
  ): Valid[Future[Done]] = {
    types.toList.map{ _ initialize props }.sequence map { init => Future.sequence( init ) map { _ => Done } }
  }
}
