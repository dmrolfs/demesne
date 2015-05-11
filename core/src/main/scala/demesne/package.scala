import scalaz._, Scalaz._


/**
  == Overview ==
  Demesne extends Akka's Persistence frameworks to provide structure to consistently build and use CQRS+ES based
  AggregateRoots. Demesne is intended to be used in an Akka system along side other Actors, and handle the component
  model and persistence for aggregates. Demesne AggregateRoots are Akka PersistentActor, and are accessed via Demesne's
  [[demesne.DomainModel]] class. Commands are submitted to [[demesne.AggregateRoot]], which if accepted and processed,
  may produce zero or many Events that are published and persisted.

  A [[demesne.DomainModel]] represents the collection of aggregates defined in a Bounded Context. Each DomainModel
  exists within a single Akka ActorSystem.

  == Using Demesne ==
  Aggregates are retrieved through one of [[demesne.DomainModel]]'s `aggregateOf` operations.

  == Building AggregateRoots ==

  == Message Envelopes ==
  Messages (Commands and Events) are wrapped with an envelope containing meta data that describes the unit of work,
  actors involved and ordering.

  Aggregates are returned from the DomainModel as an ActorRef. In order to ensure messages are sent into the domain with 
  an initial envelope use the enveloping send or !! operations. Developers need to ensure they continue using the
  envelope capability at each point otherwise the meta envelope will not follow the unit of work.
 */
package object demesne {
  type V[A] = ValidationNel[Throwable, A]


  val FactoryKey: Symbol = 'factory
  val ModelKey: Symbol = 'model
  val SystemKey: Symbol = 'system

  case object SaveSnapshot


  trait DemesneError
}
