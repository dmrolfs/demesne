package demesne

import akka.actor.Props

/**
  * Created by rolfsd on 8/31/16.
  */
package object repository {
  type AggregateRootProps = Function2[DomainModel, AggregateRootType, Props]
}
