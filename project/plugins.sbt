resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += Classpaths.sbtPluginReleases

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "0.99.7.1")

addSbtPlugin("org.scoverage" %% "sbt-coveralls" % "0.99.0")

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.2")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.11.2")

addSbtPlugin( "com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.8" )
