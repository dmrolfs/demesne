import Dependencies._

name := "demesne-examples"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akkaModule( "contrib" ),
  "com.squants"  %% "squants"  % "0.4.2",
  "com.wix" %% "accord-core" % "0.4"
)
