import sbt.Keys._
import sbt.{ModuleID, _}

object Dependencies {
  val resolutionRepos = Seq(
    "spray repo" at "http://repo.spray.io",
//    "eaio.com" at "http://repo.eaio.com/maven2",
    "omen-bintray" at "http://dl.bintray.com/omen/maven",
    "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases",
    "Sonatype OSS Releases"  at "http://oss.sonatype.org/content/repositories/releases/",
    Resolver.jcenterRepo,
    Resolver.sonatypeRepo( "snapshots" ),
    Classpaths.sbtPluginReleases,
    "dl-john-ky" at "http://dl.john-ky.io/maven/releases"
  )

  object Scope {
    def compile( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "compile" )
    def provided( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "provided" )
    def test( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "test" )
    def runtime( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "runtime" )
    def container( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "container" )
  }

  trait Module {
    def groupId: String
    def version: String
    def artifactId( id: String ): String
    def isScala: Boolean = true
    def module( id: String ): ModuleID = {
      if ( isScala ) groupId %% artifactId(id) % version
      else groupId % artifactId(id) % version
    }
  }

  trait SimpleModule extends Module {
    def artifactIdRoot: String
    override def artifactId( id: String ): String = {
      if ( id.isEmpty ) artifactIdRoot
      else s"$artifactIdRoot-$id"
    }
  }


  object omnibus extends SimpleModule {
    override val groupId: String = "com.github.dmrolfs"
    override val artifactIdRoot: String = "omnibus"
    override val version: String = "0.73-SNAPSHOT"

    def all = Seq( core, identifier, commons, akka, archetype )

    val core = module( "core" )
    val identifier = module( "identifier" )
    val commons = module( "commons" )
    val archetype = module( "archetype" )
    val akka = module( "akka" )
  }

  object akka extends SimpleModule {
    override val groupId: String = "com.typesafe.akka"
    override val artifactIdRoot: String = "akka"
    override val version: String = "2.5.14"

    def all = Seq(
      actor,
      cluster,
      clusterSharding,
      persistence,
      agent,
      slf4j
    )

    val actor = module( "actor" )
    val agent = module( "agent" )
    val cluster = module( "cluster" )
    val clusterSharding = module( "cluster-sharding" )
    val contrib = module( "contrib" )
    val persistence = module( "persistence" )
    val remote = module( "remote" )
    val slf4j = module( "slf4j" )
    val testkit = module( "testkit" )

    val kyro = "com.github.romix.akka" %% "akka-kryo-serialization" % "0.5.2"
    val leveldb = "org.iq80.leveldb" % "leveldb" % "0.10"
    val leveldbNative = "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
  }

  object cats extends SimpleModule {
    override val groupId: String = "org.typelevel"
    override val  artifactIdRoot: String = "cats"
    override val version: String = "1.2.0"

    def all = Seq( core, kernel, macros )

    val core = module( "core" )
    val kernel = module( "kernel" )
    val macros = module( "macros" )
  }

  object monix extends SimpleModule {
    override val groupId: String = "io.monix"
    override def artifactIdRoot: String = "monix"
    override val version:String = "2.3.3"

    def all = Seq( core, cats )

    val core = module( "" )
    val cats = module( "cats" )
  }

  object log {
    object scribe extends SimpleModule {
      override val groupId: String = "com.outr"
      override def artifactIdRoot: String = "scribe"
      override def version: String = "2.5.3"
      def all = Seq( core, slf4j )
      val core = module( "" )
      val slf4j = module( "slf4j" )
    }

    def all = scribe.all

//    val typesafe = "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0"

//    object logback {
//      val version = "1.2.2"
//      val classic = "ch.qos.logback" % "logback-classic" % version
//    }

//    val slf4j = "org.slf4j" % "slf4j-api" % "1.7.25"
  }

  object quality {
    val scalatest = "org.scalatest" %% "scalatest" % "3.0.5"

    object mockito {
      val version = "2.21.0"
      val core = "org.mockito" % "mockito-core" % version
    }

    object persistence {
      val inMemory = "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.1.1"
      // val testkit = "com.github.krasserm" % "akka-persistence-testkit_2.11" % "0.3.4"
    }
  }

  // val uuid = "com.eaio.uuid" % "uuid" % "3.4"
  //    val uuid = "com.github.stephenc.eaio-uuid" % "uuid" % "3.4.0"
  val scalaUuid = "io.jvm.uuid" %% "scala-uuid" % "0.2.4"
  // val bloomFilter = "com.github.alexandrnikitin" % "bloom-filter_2.11" % "0.8.0" withSources() withJavadoc()
  val config = "com.typesafe" % "config" % "1.3.3"
  val ficus = "com.iheart" %% "ficus" % "1.4.3"
  val shapeless = "com.chuusai" %% "shapeless" % "2.3.3"
  val inflector = "org.atteo" % "evo-inflector" % "1.2.2"

  val commonTestDependencies = Scope.test(
    akka.remote,
    akka.kyro,
    akka.testkit,
    akka.leveldb,
    quality.scalatest,
    quality.persistence.inMemory,
    quality.mockito.core
  )

  val commonDependencies =
    akka.all ++
    omnibus.all ++
    cats.all ++
    monix.all ++
    log.all ++
    Seq(
      scalaUuid,
      config,
      ficus,
      shapeless
    )

  val defaultDependencyOverrides: Seq[sbt.ModuleID] = Seq.empty[sbt.ModuleID]
}
