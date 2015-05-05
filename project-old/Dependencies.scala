import sbt._

object Dependencies {
  val resolutionRepos = Seq(
    "spray repo" at "http://repo.spray.io",
    "dmrolfs" at "http://dmrolfs.github.com/snapshots",
    "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases",
    "eaio releases" at "http://eaio.com/maven2",
    "Sonatype OSS Releases"  at "http://oss.sonatype.org/content/repositories/releases/",
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
  )


  def compile( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "compile" )
  def provided( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "provided" )
  def test( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "test" )
  def runtime( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "runtime" )
  def container( deps: ModuleID* ): Seq[ModuleID] = deps map ( _ % "container" )

  val sprayVersion = "1.3.3"
  def sprayModule(id: String) = "io.spray" %% id % sprayVersion

  val akkaVersion = "2.3.10"
  def akkaModule( id: String ) = "com.typesafe.akka" %% id % akkaVersion

  val akkaActor = akkaModule( "akka-actor" )
  val akkaPersistence = akkaModule( "akka-persistence-experimental" )
  val akkaSlf4j = akkaModule( "akka-slf4j" )
  val akkaAgent = akkaModule( "akka-agent" )
  val akkaTestKit = akkaModule( "akka-testkit" )
  val akkaRemote = akkaModule( "akka-remote" )
  val akkaMultiNodeTestKit = akkaModule( "akka-multi-node-testkit" )
  val akkaContrib = akkaModule( "akka-contrib" )

  val accord = "com.wix" %% "accord-core" % "0.4"
  val config = "com.typesafe" % "config" % "1.2.1"
  val evoInflector = "org.atteo" % "evo-inflector" % "1.2"
  val squants = "com.squants"  %% "squants"  % "0.4.2"
  val inMemoryJournal = "com.github.michaelpisula" %% "akka-persistence-inmemory" % "0.2.1"
  val h2Database = "com.h2database" % "h2" % "1.4.181"

  // val commonsMath = "org.apache.commons" % "commons-math3" % "3.2"
  val eaio = "com.eaio.uuid" %% "uuid" % "3.4"
  // val graphCore = "com.assembla.scala-incubator" % "graph-core_2.10" % "1.7.0"
  // val graphConstrained = "com.assembla.scala-incubator" % "graph-constrained_2.10" % "1.7.0"
  val scalalogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0"
  // val scalalogging = "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "3.0.0"
  val wealdtech = "com.wealdtech.hawk" % "hawk-core" % "1.1.1"
  // val jsonLenses = "net.virtual-void" %%  "json-lenses" % "0.5.3"
  // val json4sNative = "org.json4s" %% "json4s-native" % "3.2.7"
  val json4sJackson = "org.json4s" %% "json4s-jackson" % "3.2.7"
  val logback = "ch.qos.logback" % "logback-classic" % "1.1.3"
  // val mysqlConnector = "mysql" % "mysql-connector-java" % "5.1.25"

  val scalacache = "com.github.cb372" %% "scalacache-guava" % "0.4.0-SNAPSHOT"

  // val objectLab = "net.objectlab.kit" % "datecalc-joda" % "1.2.0"
  val pedsCommons = "com.github.dmrolfs" %% "peds-commons" % "0.1.6" % "compile" changing()
  val pedsAkka = "com.github.dmrolfs" %% "peds-akka" % "0.1.6" % "compile" changing()
  val pedsArchetype = "com.github.dmrolfs" %% "peds-archetype" % "0.1.6" % "compile" changing()
  // val scalaAsync = "org.scala-lang.modules" %% "scala-async" % "0.9.0-M4"
  // val scalaTime = "scala-time" %% "scala-time" % "0.3.2" % "compile" changing()
  val joda = "joda-time" % "joda-time" % "2.4"
  val jodaConvert = "org.joda" % "joda-convert" % "1.7"
  val scalaTime = "com.github.nscala-time" %% "nscala-time" % "1.2.0"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.1.1"
  val shapeless = "com.chuusai" %% "shapeless" % "2.1.0"

  val parboiled = "org.parboiled" %% "parboiled-scala" % "1.1.6"
  val shiro = "org.apache.shiro" % "shiro-core" % "1.2.3"

  val slick = "com.typesafe.slick" %% "slick" % "2.0.2"

  val scalatest = "org.scalatest" %% "scalatest" % "2.2.1" % "test"
  val mockito = "org.mockito" % "mockito-core" % "1.10.19"
  val specs2 = "org.specs2" %% "specs2" % "2.3.11"

  val sprayCan = sprayModule("spray-can")
  val sprayHttp = sprayModule("spray-http")
  val sprayRouting = sprayModule("spray-routing-shapeless2")
  val sprayTestKit = sprayModule("spray-testkit")
  // val sprayJson = "io.spray" %% "spray-json" % "1.2.5"
  val twirlApi = "io.spray" %% "twirl-api" % "0.6.1"
  val reactiveMongo = "org.reactivemongo" %% "reactivemongo" % "0.10.5.akka23-SNAPSHOT"
  val commonsLogging = "commons-logging" % "commons-logging" % "1.1.3"
}