import Dependencies._

name := "demesne-examples"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akka.contrib,
  akka.kyro,
  facility.squants,
  facility.accord
)
