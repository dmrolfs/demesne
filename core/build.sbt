import Dependencies._

name := "demesne-core"

description := "lorem ipsum."

libraryDependencies ++= commonDependencies ++ Seq(
  akkaModule( "contrib" ),
  "org.scalaz" %% "scalaz-concurrent" % "7.1.3",
  "com.github.dmrolfs" %% "peds-archetype" % "0.1.6" % "compile" changing(),
  "com.github.dmrolfs" %% "shapeless-builder" % "0.1-SNAPSHOT" % "compile",
  "org.atteo" % "evo-inflector" % "1.2"
)
