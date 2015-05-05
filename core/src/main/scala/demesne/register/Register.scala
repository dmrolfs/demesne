package demesne.register

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by damonrolfs on 11/5/14.
 */
trait Register[K, I] {
  //todo: path dependent types for local and shared (via Future[]) work? PredicateResult seems to be the difficult part
  type Result
  type OptionalResult = Option[Result]
  type PredicateResult

  type Entry = (K, I)

  implicit def ec: ExecutionContext

  /** Optionally returns the aggregate id associated with a key.
   * @param key the key aggregate id
   * @return an option value containing the aggregate id associated with key in this map, or None if none exists.
   */
  def get( key: K ): Option[I]

  def futureGet( key: K ): Future[Option[I]]

  /** Retrieves the value which is associated with the given key. This
    *  method invokes the `default` method of the register if there is no mapping
    *  from the given key to an id. Unless overridden, the `default` method throws a
    *  `NoSuchElementException`.
    *
    *  @param  key the key
    *  @return     the value associated with the given key, or the result of the
    *              map's `default` method, if none exists.
    */
  def apply( key: K ): I = get( key ) getOrElse default( key )

  def future( key: K ): Future[I] = futureGet( key ) map { _ getOrElse default( key ) }

  /**  Returns the aggregate id associated with a key, or a default value if the key is not contained in the register.
    *   @param   key      the key.
    *   @param   default  a computation that yields a default aggregate id in case no binding for `key` is
    *                     found in the register.
    *   @tparam  I1       the result type of the default computation.
    *   @return  the aggregate id associated with `key` if it exists, otherwise the result of the `default` computation.
    *
    *   @usecase def getOrElse( key: K, default: => I ): I
    */
  def getOrElse[I1 >: I]( key: K, default: => I1 ): I1 = get( key ) getOrElse default

  /** Tests whether this register contains a binding for a key.
    *
    *  @param key the key
    *  @return    `true` if there is a binding for `key` in this register, `false` otherwise.
    */
  def contains( key: K ): Boolean = get( key ).isDefined

  /** Tests whether this register contains a binding for a key. This method,
    *  which implements an abstract method of trait `PartialFunction`,
    *  is equivalent to `contains`.
    *
    *  @param key the key
    *  @return    `true` if there is a binding for `key` in this map, `false` otherwise.
    */
  def isDefinedAt( key: K ): Boolean = contains( key )

//  def map[BK, BI]( f: Entry => (BK, BI) ): Register[BK, BI]
//
//  def flatMap[BK, BI]( f: Entry => Register[BK, BI]): Register[BK, BI]
//
//  def foreach[U]( f: Entry => U ): Unit


  /** Defines the default value computation for the map, returned when a key is not found
    *  The method implemented here throws an exception, but it might be overridden in subclasses.
    *  @param key the given key value for which a binding is missing.
    *  @throws `NoSuchElementException`
    */
  def default( key: K ): I = throw new NoSuchElementException( "key not found: " + key )
}
