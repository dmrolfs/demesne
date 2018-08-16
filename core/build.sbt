import Dependencies._

name := "demesne-core"

description := "lorem ipsum."

scalacOptions := BuildSettings.scalacOptions

libraryDependencies ++=
  commonDependencies ++
  Seq(
    akka.contrib,
    inflector
  )

enablePlugins( BuildInfoPlugin )

buildInfoKeys := Seq[BuildInfoKey]( name, version, scalaVersion, sbtVersion )

buildInfoPackage := "demesne"

testOptions in Test += Tests.Argument( "-oDF" )
