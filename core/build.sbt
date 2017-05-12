import Dependencies._

name := "demesne-core"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akka.contrib,
  facility.inflector
)

enablePlugins( BuildInfoPlugin )

buildInfoKeys := Seq[BuildInfoKey]( name, version, scalaVersion, sbtVersion )

buildInfoPackage := "demesne"
