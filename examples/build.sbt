import Dependencies._

name := "demesne-examples"

description := "lorem ipsum."

scalacOptions := BuildSettings.scalacOptions

libraryDependencies ++=
  commonDependencies ++
  Seq(
    akka.contrib,
    akka.kyro,
    akka.leveldb,
    // facility.bloomFilter,
    "org.typelevel"  %% "squants"  % "1.3.0",
    "com.wix" %% "accord-core" % "0.7.2"
)

libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"

testOptions in Test += Tests.Argument( "-oDF" )
