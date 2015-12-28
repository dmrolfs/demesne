import sbt.Keys._
import sbt._

object Dependencies {
  val commonDependencies = Seq(
    "com.eaio.uuid" % "uuid" % "3.4",
    "com.typesafe" % "config" % "1.3.0",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    akkaModule( "actor" ),
    akkaModule( "cluster" ),
    akkaModule( "cluster-sharding" ),
    akkaModule( "persistence" ),
    akkaModule( "agent" ),
    akkaModule( "slf4j" ),
    "com.chuusai" %% "shapeless" % "2.2.5",
    "org.scalaz" %% "scalaz-core" % "7.2.0",
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "org.slf4j" % "slf4j-api" % "1.7.13",
    "com.github.dmrolfs" %% "peds-commons" % "0.1.6" % "compile" changing(),
    "com.github.dmrolfs" %% "peds-akka" % "0.1.6" % "compile" changing()
  ) ++ test(
    akkaModule( "remote" ),
    akkaModule( "testkit" ),
    "org.scalatest" %% "scalatest" % "2.2.4",
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.1.6",
    "com.github.krasserm" %% "akka-persistence-testkit" % "0.3.4",
    "org.mockito" % "mockito-core" % "1.10.19"
  )
  
  val defaultDependencyOverrides = Set(
    "org.scalaz" %% "scalaz-core" % "7.2.0"
  )

  val sprayJson = "io.spray" %% "spray-json" % "1.3.1"
  val scopt = "com.github.scopt" %% "scopt" % "3.3.0"


  def compile( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "compile" )
  def provided( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "provided" )
  def test( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "test" )
  def runtime( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "runtime" )
  def container( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "container" )

  def akkaModule( id: String ) = "com.typesafe.akka" %% s"akka-$id" % "2.4.1"
  def peds( id: String ) = "com.github.dmrolfs" %% s"peds-$id" % "0.1.6" % "compile" changing()
}
