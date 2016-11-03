package demesne.index

import scala.concurrent.{ExecutionContext, Future}


/**
 * Created by damonrolfs on 11/5/14.
 */
abstract class Index[K, I, V] {
  //todo: path dependent types for local and shared (via Future[]) work? PredicateResult seems to be the difficult part
//  type Result
//  type OptionalResult = Option[Result]
//  type PredicateResult

//  type Entry = (K, IndexedValue[I, V])

  implicit val ec: ExecutionContext

  /** Returns all of the current aggregate id key entries.
    * @return a map containing the aggregate ids and associated keys.
    */
  def entries: Map[K, V] = indexedValueEntries mapValues { _.value }
  def indexedValueEntries: Map[K, IndexedValue[I, V]]

  def futureEntries: Future[Map[K, V]] = futureIndexedValueEntries map { _ mapValues { _.value } }
  def futureIndexedValueEntries: Future[Map[K, IndexedValue[I, V]]]

  /** Optionally returns the aggregate id associated with a key.
   * @param key the key aggregate id
   * @return an option value containing the aggregate id associated with key in this map, or None if none exists.
   */
  def get( key: K ): Option[V] = getIndexedValue( key ) map { _.value }
  def getIndexedValue( key: K ): Option[IndexedValue[I, V]]

  def futureGet( key: K ): Future[Option[V]] = futureGetIndexedValue( key ) map { _ map { _.value } }
  def futureGetIndexedValue( key: K ): Future[Option[IndexedValue[I, V]]]

  /** Retrieves the value which is associated with the given key. This
    *  method invokes the `default` method of the index if there is no mapping
    *  from the given key to an id. Unless overridden, the `default` method throws a
    *  `NoSuchElementException`.
    *
    *  @param  key the key
    *  @return     the value associated with the given key, or the result of the
    *              map's `default` method, if none exists.
    */
  def apply( key: K ): V = get( key ) getOrElse default( key )

  def future( key: K ): Future[V] = futureGet( key ) map { _ getOrElse default( key ) }

  /**  Returns the aggregate id associated with a key, or a default value if the key is not contained in the index.
    *   @param   key      the key.
    *   @param   default  a computation that yields a default aggregate id in case no binding for `key` is
    *                     found in the index.
    *   @tparam  V1       the result type of the default computation.
    *   @return  the aggregate value associated with `key` if it exists, otherwise the result of the `default` computation.
    *
    *   @usecase def getOrElse( key: K, default: => V ): V
    */
  def getOrElse[V1 >: V]( key: K, default: => V1 ): V1 = get( key ) getOrElse default

  /** Tests whether this index contains a binding for a key.
    *
    *  @param key the key
    *  @return    `true` if there is a binding for `key` in this index, `false` otherwise.
    */
  def contains( key: K ): Boolean = get( key ).isDefined

  /** Tests whether this index contains a binding for a key. This method,
    *  which implements an abstract method of trait `PartialFunction`,
    *  is equivalent to `contains`.
    *
    *  @param key the key
    *  @return    `true` if there is a binding for `key` in this map, `false` otherwise.
    */
  def isDefinedAt( key: K ): Boolean = contains( key )

//  def map[BK, BI]( f: Entry => (BK, BI) ): Index[BK, BI]
//
//  def flatMap[BK, BI]( f: Entry => Index[BK, BI]): Index[BK, BI]
//
//  def foreach[U]( f: Entry => U ): Unit


  /** Defines the default value computation for the map, returned when a key is not found
    *  The method implemented here throws an exception, but it might be overridden in subclasses.
    *  @param key the given key value for which a binding is missing.
    *  
    */
  @throws(classOf[NoSuchElementException])
  def default( key: K ): V = throw new NoSuchElementException( "key not found: " + key )
}
