package example

import scala.reflect.ClassTag
import org.scalatest._
import org.scalatest.matchers.ShouldMatchers
import shapeless._

import com.github.harveywi.builder._


object ExampleSpec {
  trait Entity {
    type ID
    def id: ID
    def name: String
  }

  trait EntityCompanion[E <: Entity] {
    def idLens: Lens[E, E#ID]
    def nameLens: Lens[E, String]
  }


  trait Foo extends Entity {
    override type ID = Long
    def f: Int
  }

  object Foo extends EntityCompanion[Foo] {
    override val idLens: Lens[Foo, Foo#ID] = new Lens[Foo, Foo#ID] {
      override def get( f: Foo ): Foo#ID = f.id
      override def set( f: Foo )( id: Foo#ID ): Foo = FooImpl( id = id, name = f.name, f = f.f )
    }

    override val nameLens: Lens[Foo, String] = new Lens[Foo, String] {
      override def get( f: Foo ): String = f.name
      override def set( f: Foo )( n: String ): Foo = FooImpl( id = f.id, name = n, f = f.f )
    }
  }

  case class FooImpl( override val id: Long, override val name: String, override val f: Int ) extends Foo


  trait Module[E <: Entity] {
    def tag: Symbol
    def idLens: Lens[E, E#ID]
    def nameLens: Lens[E, String]
    def target: Class[_]
  }

  object Module {
    def builderFor[E <: Entity : ClassTag]: BuilderFactory[E] = new BuilderFactory[E]

    class BuilderFactory[E <: Entity : ClassTag] {
      type CC = ModuleImpl[E]

      def make[L <: HList]( implicit g: Generic.Aux[CC, L] ): ModuleBuilder[L] = new ModuleBuilder[L]


      class ModuleBuilder[L <: HList]( implicit val g: Generic.Aux[CC, L] ) extends HasBuilder[CC] {
        object P {
          object Tag extends OptParam[Symbol]( 'DefaultFooTAG )
          object IdLens extends Param[Lens[E, E#ID]]
          object NameLens extends Param[Lens[E, String]]
        }

        override val gen = Generic[CC]
        override val fieldsContainer = createFieldsContainer( P.Tag :: P.IdLens :: P.NameLens :: HNil )
      }
    }
  }

  // this sub-module is required to provide enough information for the compiler to generate a Generic value for this
  // builder, which had difficulty with the E#TID type param of the idLens property.
  // I couldn't repro this issue in an isolated manner, and the solution was crafted through lots of experiementation
  // after digging through results from -Xlog-implicits and -Ymacro-debug-verbose.
  object ModuleImpl {
    type ID[E <: Entity] = E#ID
  }

  final case class ModuleImpl[E <: Entity : ClassTag](
    override val tag: Symbol,
    override val idLens: Lens[E, ModuleImpl.ID[E]],
    override val nameLens: Lens[E, String]
  ) extends Module[E] {
    override val target: Class[_] = implicitly[ClassTag[E]].runtimeClass
  }
}

class ExampleSpec extends FlatSpec with Matchers {
  import ExampleSpec._

  "when building module" should "generate expected Module implementation" in {
    val expected = ModuleImpl[Foo]( tag = 'fooTAG, idLens = Foo.idLens, nameLens = Foo.nameLens )

    val builder = Module.builderFor[Foo].make
    import builder.P._

    val module = builder.builder
                        .set( Tag, 'fooTAG )
                        .set( IdLens, Foo.idLens )
                        .set( NameLens, Foo.nameLens )
                        .build()

    val f = FooImpl( 314159, "foobar", 17 )
    module should equal( expected )
    module.tag should equal( expected.tag )
    module.idLens.get(f) should equal( expected.idLens.get(f) )
    module.nameLens.get(f) should equal( expected.nameLens.get(f) )
    module.target should equal( classOf[Foo] )
    module.target should equal( expected.target )
  }
}
