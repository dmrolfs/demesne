package demesne.repository

import demesne.AggregateRootType


/**
  * Created by rolfsd on 8/19/16.
  */
sealed trait StartProtocol

object StartProtocol {
  case object Load extends StartProtocol

  case class Loaded(
    rootType: AggregateRootType,
    resources: Map[Symbol, Any] = Map.empty[Symbol, Any],
    dependencies: Set[Symbol] = Set.empty[Symbol]
  ) extends StartProtocol {
    override def toString: String = {
      s"""Loaded(${rootType.name} dependencies:[${dependencies.mkString(", ")}] resources:[${resources.keySet.mkString(", ")}])"""
    }
  }

  case class Initialize( resources: Map[Symbol, Any] ) extends StartProtocol {
    override def toString: String = {
      s"""Initialize( resources:[${resources.keySet.mkString(", ")}] )"""
    }
  }

  case object WaitForStart extends StartProtocol

  case object Started extends StartProtocol

  case object GetStatus extends StartProtocol

  case class StartStatus( repositories: Map[String, RepositorySupervisor.RepositoryStartupState] ) extends StartProtocol {
    override def toString: String = s"""StartStatus( ${repositories.map{ case (r, s) => r+":"+s }.mkString("[", ", ", "]")} )"""
  }
}
