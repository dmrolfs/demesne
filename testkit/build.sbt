import Dependencies._

name := "demesne-testkit"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akka.contrib,
  akka.remote,
  akka.testkit,
  qa.scalatest,
  qa.mockito.core,
  qa.persistence.inMemory,
  qa.persistence.testkit,
  facility.inflector
)
