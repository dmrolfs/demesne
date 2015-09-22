import Dependencies._

name := "demesne-examples"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akkaModule( "contrib" ),
  "com.squants"  %% "squants"  % "0.5.3",
  "com.wix" %% "accord-core" % "0.5"
)
