import Dependencies._

name := "demesne-examples"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akka.contrib,
  akka.kyro,
  akka.leveldb,
  facility.bloomFilter,
  facility.squants,
  facility.accord
)
