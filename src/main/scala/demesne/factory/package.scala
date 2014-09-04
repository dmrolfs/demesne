package demesne

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.contrib.pattern.ClusterSharding
import peds.commons.log.Trace
import peds.commons.util._


package object factory {
  type ActorFactory = (ActorSystem, String) => ( => Props ) => ActorRef

  // val systemFactory: ActorFactory = (system: ActorSystem, name: String) => ( props: => Props ) => system.actorOf( props, name )
  def systemFactory( system: ActorSystem, name: String )( props: => Props ): ActorRef = system.actorOf( props, name )

  // val clusteredFactory: ActorFactory = (system: ActorSystem, name: String) => ( props: => Props ) => {
  //   ClusterSharding( system ) shardRegion name
  // }
  def clusteredFactory( system: ActorSystem, name: String )( props: => Props ): ActorRef = {
    ClusterSharding( system ) shardRegion name
  }
}
