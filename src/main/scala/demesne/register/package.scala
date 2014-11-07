package demesne


/**
 * Created by damonrolfs on 11/5/14.
 */
package object register {
  import scala.language.existentials
  case class FinderSpec( key: Class[_], id: Class[_] )

  sealed trait RegisterMessage
  case object GetRegister extends RegisterMessage
  case class RegisterEnvelope( payload: Any ) extends RegisterMessage {
    def mapTo[K, I]: Register[K, I] = payload.asInstanceOf[Register[K, I]]
  }
}
