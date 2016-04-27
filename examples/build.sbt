import Dependencies._

name := "demesne-examples"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akka.contrib,
  facility.squants,
  facility.accord
)
