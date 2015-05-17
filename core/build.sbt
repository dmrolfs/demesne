import Dependencies._

name := "demesne-core"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akkaModule( "contrib" ),
  "org.atteo" % "evo-inflector" % "1.2"
)
