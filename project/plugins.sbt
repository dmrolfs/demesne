//resolvers += Resolver.sonatypeRepo("snapshots")
//
//resolvers += Classpaths.sbtPluginReleases

//addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")

libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.25"

logLevel := Level.Warn

addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.4.0")

addSbtPlugin( "com.eed3si9n" % "sbt-buildinfo" % "0.8.0" )

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.7")

addSbtPlugin("org.foundweekends" % "sbt-bintray"            % "0.5.3")
//addSbtPlugin("me.lessis"                         % "bintray-sbt"            % "0.3.0")

addSbtPlugin("com.geirsson" % "sbt-scalafmt" % "1.5.1")
//addSbtPlugin("com.github.gseitz"                 % "sbt-release"            % "1.0.7")

addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"       % "2.0.2")
//addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"       % "1.1.0")

addSbtPlugin("com.timushev.sbt" % "sbt-updates"            % "0.3.4")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph"   % "0.9.0")
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0")
addSbtPlugin("io.get-coursier" % "sbt-coursier"           % "1.0.1")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.2.5")

// addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.11")

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)

