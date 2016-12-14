import sbt.Keys._
import sbt._

object Dependencies {
  object peds {
    val version = "0.4.3"
    def module( id: String ) = "com.github.dmrolfs" %% s"peds-$id" % version

    val commons = module( "commons" )
    val archetype = module( "archetype" )
    val akka = module( "akka" )
  }

  object akka {
    val version = "2.4.14"
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

    val kyro = "com.github.romix.akka" %% "akka-kryo-serialization" % "0.5.0"
    val leveldb = "org.iq80.leveldb" % "leveldb" % "0.7"
    val leveldbNative = "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
  }

  object scalaz {
    val version = "7.2.8"
    def module( id: String ) = "org.scalaz" %% s"scalaz-$id" % version

    val core = module( "core" )
    val concurrent = module( "concurrent" )
  }

  object log {
    val typesafe = "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0"

    object logback {
      val version = "1.1.7"
      val classic = "ch.qos.logback" % "logback-classic" % version
    }

    val slf4j = "org.slf4j" % "slf4j-api" % "1.7.21"
  }

  object facility {
    val uuid = "com.eaio.uuid" % "uuid" % "3.4"
    val config = "com.typesafe" % "config" % "1.3.1"
    val shapeless = "com.chuusai" %% "shapeless" % "2.3.2"
    val inflector = "org.atteo" % "evo-inflector" % "1.2.1"
    val squants = "com.squants"  % "squants_2.11"  % "0.6.2"
    val accord = "com.wix" %% "accord-core" % "0.6.1"
  }

  object qa {
    val scalatest = "org.scalatest" %% "scalatest" % "3.0.1"

    object mockito {
      val version = "1.10.19"
      val core = "org.mockito" % "mockito-core" % "1.10.19"
    }

    object persistence {
      val inMemory = "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.3.17"
      // val testkit = "com.github.krasserm" % "akka-persistence-testkit_2.11" % "0.3.4"
    }
  }

  val commonDependencies = Seq(
    facility.uuid,
    facility.config,
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
    scalaz.core,
    peds.commons,
    peds.archetype,
    peds.akka
  ) ++ test(
    akka.remote,
    akka.kyro,
    akka.testkit,
    akka.leveldb,
    qa.scalatest,
    qa.persistence.inMemory,
    qa.mockito.core
  )
  
  val defaultDependencyOverrides = Set(
    scalaz.core
  )

  val sprayJson = "io.spray" %% "spray-json" % "1.3.1"
  val scopt = "com.github.scopt" %% "scopt" % "3.3.0"


  def compile( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "compile" )
  def provided( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "provided" )
  def test( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "test" )
  def runtime( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "runtime" )
  def container( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "container" )

}
