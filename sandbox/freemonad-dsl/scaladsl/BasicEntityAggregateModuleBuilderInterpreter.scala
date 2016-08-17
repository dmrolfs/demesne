package demesne.scaladsl

import scala.reflect.ClassTag
import scalaz.{ Lens => _, _ }, Scalaz._
import shapeless.Lens
import shapeless.syntax.typeable._
import peds.archetype.domain.model.core.Entity
import peds.commons.log.Trace
import peds.commons.util._
import demesne.module.{ AggregateRootProps, BasicEntityAggregateModule }


object BasicEntityAggregateModuleBuilderInterpreter {
  def apply[S <: Entity : ClassTag]: BasicEntityAggregateModuleBuilderInterpreter[S] = new BasicEntityAggregateModuleBuilderInterpreter[S]()
}

class BasicEntityAggregateModuleBuilderInterpreter[S <: Entity : ClassTag]() extends SimpleAggregateModuleBuilderInterpreter[S] {
  trait BasicEntityModule extends SimpleModule with BasicEntityAggregateModule[S] {
    import BasicEntityModule._

    override def idLens: Lens[S, S#TID] = {
      trace( s"ID lens = $idLensO" )
      idLensO map { _.asInstanceOf[Lens[S, S#TID]] } getOrElse { throw UndefinedLensError( "id" ) }
    }
    def idLensO: Option[Lens[_, _]]
    def idLensOLens: Lens[BasicEntityModule, Option[Lens[_, _]]]

    override def nameLens: Lens[S, String] = {
      trace( s"NAME lens = $nameLensO" )
      nameLensO map { _.asInstanceOf[Lens[S, String]] } getOrElse { throw UndefinedLensError( "name" ) }
    }
    def nameLensO: Option[Lens[_, String]]
    def nameLensOLens: Lens[BasicEntityModule, Option[Lens[_, String]]]

    override def slugLens: Lens[S, String] = {
      trace( s"SLUG lens = $slugLensO" )
      slugLensO map { _.asInstanceOf[Lens[S, String]] } getOrElse { throw UndefinedLensError( "slug" ) }
    }
    def slugLensO: Option[Lens[_, String]]
    def slugLensOLens: Lens[BasicEntityModule, Option[Lens[_, String]]]

    def isActiveLens: Lens[S, Boolean] = {
      trace( s"IS_ACTIVE lens = $isActiveLensO" )
      isActiveLensO map { _.asInstanceOf[Lens[S, Boolean]] } getOrElse { throw UndefinedLensError( "isActive" ) }
    }
    def isActiveLensO: Option[Lens[_, Boolean]]
    def isActiveLensOLens: Lens[BasicEntityModule, Option[Lens[_, Boolean]]]
  }

  object BasicEntityModule {
    case class UndefinedLensError private[scaladsl]( lens: String ) 
    extends IllegalArgumentException( s"Must define lens [$lens] before building module" ) with demesne.module.DemesneModuleError
  }


  case class BasicEntityModuleImpl private[scaladsl](
    override val idTagO: Option[Symbol] = None,
    override val indexes: Seq[IndexSpecification] = Seq.empty[IndexSpecification],
    override val propsO: Option[AggregateRootProps] = None,
    override val idLensO: Option[Lens[_, _]] = None,
    override val nameLensO: Option[Lens[_, String]] = None,
    override val slugLensO: Option[Lens[_, String]] = None,
    override val isActiveLensO: Option[Lens[_, Boolean]] = None
  ) extends BasicEntityModule {
    override def trace: Trace[_] = Trace[BasicEntityModuleImpl]
    override def evState: ClassTag[S] = implicitly[ClassTag[S]]

    override val idTagOLens: Lens[BuilderModule, Option[Symbol]] = new Lens[BuilderModule, Option[Symbol]] {
      override def get( s: BuilderModule ): Option[Symbol] = s.idTagO
      override def set( s: BuilderModule )( a: Option[Symbol] ): BuilderModule = {
        BasicEntityModuleImpl( 
          idTagO = a, 
          indexes = s.indexes, 
          propsO = s.propsO, 
          idLensO = s.cast[BasicEntityModule].flatMap( _.idLensO ), 
          nameLensO = s.cast[BasicEntityModule].flatMap( _.nameLensO ), 
          slugLensO = s.cast[BasicEntityModule].flatMap( _.slugLensO ), 
          isActiveLensO = s.cast[BasicEntityModule].flatMap( _.isActiveLensO )
        )
      }
    }

    override val propsOLens: Lens[BuilderModule, Option[AggregateRootProps]] = new Lens[BuilderModule, Option[AggregateRootProps]] {
      override def get( s: BuilderModule ): Option[AggregateRootProps] = s.propsO
      override def set( s: BuilderModule )( a: Option[AggregateRootProps] ): BuilderModule = {
        BasicEntityModuleImpl( 
          idTagO = s.idTagO, 
          indexes = s.indexes, 
          propsO = a, 
          idLensO = s.cast[BasicEntityModule].flatMap( _.idLensO ), 
          nameLensO = s.cast[BasicEntityModule].flatMap( _.nameLensO ), 
          slugLensO = s.cast[BasicEntityModule].flatMap( _.slugLensO ), 
          isActiveLensO = s.cast[BasicEntityModule].flatMap( _.isActiveLensO )
        )
      }
    }

    override val indexesLens: Lens[SimpleModule, Seq[IndexSpecification]] = {
      new Lens[SimpleModule, Seq[IndexSpecification]] {
        override def get( s: SimpleModule ): Seq[IndexSpecification] = s.indexes
        override def set( s: SimpleModule )( a: Seq[IndexSpecification] ): SimpleModule = {
          BasicEntityModuleImpl( 
            idTagO = s.idTagO, 
            indexes = a, 
            propsO = s.propsO, 
            idLensO = s.cast[BasicEntityModule].flatMap( _.idLensO ), 
            nameLensO = s.cast[BasicEntityModule].flatMap( _.nameLensO ), 
            slugLensO = s.cast[BasicEntityModule].flatMap( _.slugLensO ), 
            isActiveLensO = s.cast[BasicEntityModule].flatMap( _.isActiveLensO )
          )
        }
      }
    }

    override def idLensOLens: Lens[BasicEntityModule, Option[Lens[_, _]]] = new Lens[BasicEntityModule, Option[Lens[_, _]]] {
      override def get( s: BasicEntityModule ): Option[Lens[_, _]] = s.idLensO
      override def set( s: BasicEntityModule )( a: Option[Lens[_, _]] ): BasicEntityModule = {
        BasicEntityModuleImpl( 
          idTagO = s.idTagO, 
          indexes = s.indexes, 
          propsO = s.propsO, 
          idLensO = a, 
          nameLensO = s.nameLensO, 
          slugLensO = s.slugLensO, 
          isActiveLensO = s.isActiveLensO 
        )
      }
    }

    override def nameLensOLens: Lens[BasicEntityModule, Option[Lens[_, String]]] = {
      new Lens[BasicEntityModule, Option[Lens[_, String]]] {
        override def get( s: BasicEntityModule ): Option[Lens[_, String]] = s.nameLensO
        override def set( s: BasicEntityModule )( a: Option[Lens[_, String]] ): BasicEntityModule = {
          BasicEntityModuleImpl( 
            idTagO = s.idTagO, 
            indexes = s.indexes, 
            propsO = s.propsO, 
            idLensO = s.idLensO, 
            nameLensO = a, 
            slugLensO = s.slugLensO, 
            isActiveLensO = s.isActiveLensO 
          )
        }
      }
    }

    override def slugLensOLens: Lens[BasicEntityModule, Option[Lens[_, String]]] = {
      new Lens[BasicEntityModule, Option[Lens[_, String]]] {
        override def get( s: BasicEntityModule ): Option[Lens[_, String]] = s.slugLensO
        override def set( s: BasicEntityModule )( a: Option[Lens[_, String]] ): BasicEntityModule = {
          BasicEntityModuleImpl( 
            idTagO = s.idTagO, 
            indexes = s.indexes, 
            propsO = s.propsO, 
            idLensO = s.idLensO, 
            nameLensO = s.nameLensO, 
            slugLensO = a, 
            isActiveLensO = s.isActiveLensO 
          )
        }
      }
    }

    override def isActiveLensOLens: Lens[BasicEntityModule, Option[Lens[_, Boolean]]] = {
      new Lens[BasicEntityModule, Option[Lens[_, Boolean]]] {
        override def get( s: BasicEntityModule ): Option[Lens[_, Boolean]] = s.isActiveLensO
        override def set( s: BasicEntityModule )( a: Option[Lens[_, Boolean]] ): BasicEntityModule = {
          BasicEntityModuleImpl( 
            idTagO = s.idTagO, 
            indexes = s.indexes, 
            propsO = s.propsO, 
            idLensO = s.idLensO, 
            nameLensO = s.nameLensO, 
            slugLensO = s.slugLensO, 
            isActiveLensO = a
          )
        }
      }
    }

    override def toString: String = {
      s"${getClass.safeSimpleName}[${stateClass.safeSimpleName}](${aggregateIdTag} " +
      s"""indexes=${indexes.map(_.name).mkString("[", ",", "]")})"""
    }
  }


  private var _module: BasicEntityModuleImpl = BasicEntityModuleImpl()
  override def module: BuilderModule = _module
  override def module_=( newModule: BuilderModule ): Unit = {
    _module = newModule match {
      case m: BasicEntityModuleImpl => m
      case m: BasicEntityModule => m.asInstanceOf[BasicEntityModuleImpl]  // remove since unnecessary
      case m: SimpleModule => BasicEntityModuleImpl( idTagO = m.idTagO, indexes = m.indexes, propsO = m.propsO )
      case m: BuilderModule => BasicEntityModuleImpl( idTagO = m.idTagO, indexes = m.indexes, propsO = m.propsO )
    }
  }

  def basicEntityOpStep[A]: PartialFunction[ModuleBuilderOpF[A], (Id[A], BuilderModule)] = {
    case SetIdLens( idLens, next ) => ( next, _module.copy(idLensO = Some(idLens)) )
    case SetNameLens( nameLens, next ) => ( next, _module.copy(nameLensO = Some(nameLens)) )
    case SetSlugLens( slugLens, next ) => ( next, _module.copy(slugLensO = Some(slugLens)) )
    case SetIsActiveLens( isActiveLens, next ) => ( next , _module.copy(isActiveLensO = Some(isActiveLens)) )
  }

  override def opStep[A]: PartialFunction[ModuleBuilderOpF[A], (Id[A], BuilderModule)] = {
    basicEntityOpStep[A] orElse simpleOpStep[A] orElse super.opStep[A]
  }

  import BasicEntityAggregateModuleBuilder._

  override def apply[A]( action: ModuleBuilderOp[A] ): Id[A] = action.runM( step )
}
