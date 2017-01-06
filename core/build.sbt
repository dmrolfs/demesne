import Dependencies._

name := "demesne-core"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akka.contrib,
  scalaz.concurrent,
  facility.inflector
)

enablePlugins( BuildInfoPlugin )

buildInfoKeys := Seq[BuildInfoKey]( name, version, scalaVersion, sbtVersion )

buildInfoPackage := "demesne"
