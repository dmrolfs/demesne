import sbt.Keys._
import sbt._

object Dependencies {
  val commonDependencies = Seq(
    "com.eaio.uuid" % "uuid" % "3.4",
    "com.typesafe" % "config" % "1.2.1",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    akkaModule( "actor" ),
    akkaModule( "persistence-experimental" ),
    akkaModule( "agent" ),
    akkaModule( "slf4j" ),
    "com.chuusai" %% "shapeless" % "2.1.0",
    "org.scalaz" %% "scalaz-core" % "7.1.3",
    "org.typelevel" %% "scalaz-contrib-210"        % "0.2",
    "org.typelevel" %% "scalaz-contrib-validation" % "0.2",
    "org.typelevel" %% "scalaz-contrib-undo"       % "0.2",
    // currently unavailable because there's no 2.11 build of Lift yet
    // "org.typelevel" %% "scalaz-lift"               % "0.2",
    "org.typelevel" %% "scalaz-nscala-time"        % "0.2",
    "org.typelevel" %% "scalaz-spire"              % "0.2",
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "org.slf4j" % "slf4j-api" % "1.7.12",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    "com.github.dmrolfs" %% "peds-commons" % "0.1.6" % "compile" changing(),
    "com.github.dmrolfs" %% "peds-akka" % "0.1.6" % "compile" changing()
    // "org.atteo" % "evo-inflector" % "1.2",
  ) ++ test( 
    akkaModule( "remote" ),
    akkaModule( "testkit" ),
    "org.scalatest" %% "scalatest" % "2.2.1",
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.0.0",
    // "com.github.michaelpisula" %% "akka-persistence-inmemory" % "0.2.1",
    "com.github.krasserm" %% "akka-persistence-testkit" % "0.3.4",
    "org.mockito" % "mockito-core" % "1.10.19"
  )
  
  val defaultDependencyOverrides = Set(
      "org.scala-lang" % "scala-reflect" % "2.11.4",
      // "com.google.guava" % "guava" % "15.0",
      "org.scala-lang" % "scala-library" % "2.11.4",
      "org.scala-lang" % "scalap" % "2.11.4",
      "org.scala-lang" % "scala-compiler" % "2.11.4",
      "org.scala-lang" % "scala-xml" % "2.11.4",
      "com.chuusai" %% "shapeless" % "2.1.0",
      "org.scalaz" %% "scalaz-core" % "7.1.1",
      "org.slf4j" % "slf4j-api" % "1.7.12",
      "org.parboiled" %% "parboiled-scala" % "1.1.7",
      "com.typesafe" % "config" % "1.2.1",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
      "org.scalatest" %% "scalatest" % "2.2.1" % "test",
      "org.mockito" % "mockito-core" % "1.10.19" % "test"
    )

  val slf4j = "org.slf4j" % "slf4j-api" % "1.7.12"
  val logbackVersion = "1.1.2"
  val logbackCore = "ch.qos.logback" % "logback-core" % logbackVersion
  val logbackClassic = "ch.qos.logback" % "logback-classic" % logbackVersion
  val loggingImplementations = Seq( logbackCore, logbackClassic )

  val sprayJson = "io.spray" %% "spray-json" % "1.3.1"

  val scopt = "com.github.scopt" %% "scopt" % "3.3.0"


  def compile( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "compile" )
  def provided( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "provided" )
  def test( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "test" )
  def runtime( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "runtime" )
  def container( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "container" )

  def akkaModule( id: String ) = "com.typesafe.akka" %% s"akka-$id" % "2.3.11"
  def sprayModule( id: String ) = "io.spray" %% s"spray-$id" % "1.3.3"
  def peds( id: String ) = "com.github.dmrolfs" %% s"peds-$id" % "0.1.6" % "compile" changing()
}
