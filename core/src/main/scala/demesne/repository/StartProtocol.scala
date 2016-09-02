package demesne.repository

import demesne.AggregateRootType


/**
  * Created by rolfsd on 8/19/16.
  */
sealed trait StartProtocol

object StartProtocol {
  case object Load extends StartProtocol

  case class Loaded( rootType: AggregateRootType, resources: Map[Symbol, Any], dependencies: Set[Symbol] ) extends StartProtocol {
    override def toString: String = {
      s"""Loaded(${rootType.name} dependencies:[${dependencies.mkString(", ")}] resources:[${resources.mkString(", ")}])"""
    }
  }

  case class Initialize( resources: Map[Symbol, Any] ) extends StartProtocol {
    override def toString: String = {
      s"""Initialize( resources:[${resources.mkString(", ")}] )"""
    }
  }

  case object WaitForStart extends StartProtocol

  case object Started extends StartProtocol
}
