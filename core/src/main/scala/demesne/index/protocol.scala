package demesne.index


/**
  * base index protocol type
  */
sealed trait IndexMessage

/**
  * marks the beginning of initializing the local index agent.
  */
case object WaitingForStart extends IndexMessage

/**
  * notifies each local listener that the agent has completed initialization and is ready
  */
case object Started extends IndexMessage

/**
  * request index agent to be used in subscriber
  */
case object GetIndex extends IndexMessage

/**
  * envelope message used to deliver the index akka agent
  */
case class IndexEnvelope( payload: Any ) extends IndexMessage {
  def mapTo[K, I, V]: Index[K, I, V] = payload.asInstanceOf[Index[K, I, V]]
}
