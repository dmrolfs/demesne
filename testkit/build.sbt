import Dependencies._

name := "demesne-testkit"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akkaModule( "remote" ),
  akkaModule( "testkit" ),
  "org.scalatest" %% "scalatest" % "2.2.4",
  "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.0.5",
  "com.github.krasserm" %% "akka-persistence-testkit" % "0.3.4",
  "org.mockito" % "mockito-core" % "1.10.19",
  akkaModule( "contrib" ),
  "org.atteo" % "evo-inflector" % "1.2.1"
)
