import sbt.Keys._
import sbt._

object Dependencies {
  val resolutionRepos = Seq(
    "spray repo" at "http://repo.spray.io",
    "eaio.com" at "http://repo.eaio.com/maven2",
    "omen-bintray" at "http://dl.bintray.com/omen/maven",
    "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases",
    "dl-john-ky" at "http://dl.john-ky.io/maven/releases",
    "OSS JFrog Artifactory" at "http://oss.jfrog.org/artifactory/oss-snapshot-local"
  )


  object omnibus {
    val version = "0.60"
    def module( id: String ) = "com.github.dmrolfs" %% s"omnibus-$id" % version

    val commons = module( "commons" )
    val archetype = module( "archetype" )
    val akka = module( "akka" )
  }

  object akka {
    val version = "2.5.1"
    def module( id: String ) = "com.typesafe.akka" %% s"akka-$id" % version

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
    val leveldb = "org.iq80.leveldb" % "leveldb" % "0.9"
    val leveldbNative = "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
  }

  object cats {
    val version = "0.9.0"
    def module( id: String ) = "org.typelevel" %% s"cats-${id}" % version

    val core = module( "core" )
    val kernel = module( "kernel" )
    val macros = module( "macros" )

    val all = Seq( core, kernel, macros )
  }

  object monix {
    val version = "2.3.0"
    def module( id: String ) = "io.monix" %% s"""monix${if (id.nonEmpty) '-'+id else "" }""" % version

    val core = module( "" )
    val cats = module( "cats" )

    val all = Seq( core, cats )
  }

  object log {
    val typesafe = "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0"

    object logback {
      val version = "1.2.2"
      val classic = "ch.qos.logback" % "logback-classic" % version
    }

    val slf4j = "org.slf4j" % "slf4j-api" % "1.7.25"
  }

  object facility {
    // val uuid = "com.eaio.uuid" % "uuid" % "3.4"
    val uuid = "com.github.stephenc.eaio-uuid" % "uuid" % "3.4.0"
    // val bloomFilter = "com.github.alexandrnikitin" % "bloom-filter_2.11" % "0.8.0" withSources() withJavadoc()
    val config = "com.typesafe" % "config" % "1.3.1"
    val ficus = "com.iheart" %% "ficus" % "1.4.0"
    val shapeless = "com.chuusai" %% "shapeless" % "2.3.2"
    val inflector = "org.atteo" % "evo-inflector" % "1.2.2"
    val squants = "org.typelevel"  %% "squants"  % "1.2.0"
    val accord = "com.wix" %% "accord-core" % "0.6.1"
  }

  object qa {
    val scalatest = "org.scalatest" %% "scalatest" % "3.0.1"

    object mockito {
      val version = "2.7.22"
      val core = "org.mockito" % "mockito-core" % version
    }

    object persistence {
      val inMemory = "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.1.0"
      // val testkit = "com.github.krasserm" % "akka-persistence-testkit_2.11" % "0.3.4"
    }
  }

  val commonDependencies = cats.all ++ monix.all ++ Seq(
    facility.uuid,
    facility.config,
    facility.ficus,
    facility.shapeless,
    log.typesafe,
    log.logback.classic,
    log.slf4j,
    akka.actor,
    akka.cluster,
    akka.clusterSharding,
    akka.persistence,
    akka.agent,
    akka.slf4j,
    omnibus.commons,
    omnibus.archetype,
    omnibus.akka
  ) ++ test(
    akka.remote,
    akka.kyro,
    akka.testkit,
    akka.leveldb,
    qa.scalatest,
    qa.persistence.inMemory,
    qa.mockito.core
  )

  val defaultDependencyOverrides = Set.empty[sbt.ModuleID]

  // val sprayJson = "io.spray" %% "spray-json" % "1.3.1"
  // val scopt = "com.github.scopt" %% "scopt" % "3.3.0"


  def compile( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "compile" )
  def provided( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "provided" )
  def test( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "test" )
  def runtime( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "runtime" )
  def container( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "container" )

}
