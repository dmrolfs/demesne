resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += Classpaths.sbtPluginReleases

addSbtPlugin( "com.typesafe.sbt" % "sbt-pgp" % "0.8.3" )

addSbtPlugin( "org.allenai.plugins" % "allenai-sbt-release" % "2014.11.06-0" )
//addSbtPlugin( "org.allenai.plugins" % "allenai-sbt-release" % "2015.05.14-1" )

addSbtPlugin( "com.typesafe.sbt" % "sbt-scalariform" % "1.3.0" )

addSbtPlugin( "io.spray" % "sbt-revolver" % "0.7.2" )

// Native packager, for deploys.
addSbtPlugin( "com.typesafe.sbt" % "sbt-native-packager" % "1.0.0-M1" )

// Check for updates.
addSbtPlugin( "com.timushev.sbt" % "sbt-updates" % "0.1.7" )

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.4.0")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.11")

addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC2")
