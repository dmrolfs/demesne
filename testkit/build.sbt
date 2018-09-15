import Dependencies._

name := "demesne-testkit"

description := "lorem ipsum."

scalacOptions := BuildSettings.scalacOptions

libraryDependencies ++=
  commonDependencies ++
  Seq(
    akka.contrib,
    akka.remote,
    akka.testkit,
    quality.scalatest,
    quality.mockito.core,
    quality.persistence.inMemory,
    inflector
  )

testOptions in Test += Tests.Argument( "-oDF" )
