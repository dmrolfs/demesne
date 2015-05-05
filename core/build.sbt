import Dependencies._
import sbtrelease.ReleasePlugin._

releaseSettings

name := "bellwether-core"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
    akkaModule( "contrib" ),
    "org.atteo" % "evo-inflector" % "1.2"
  )
