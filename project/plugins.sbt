resolvers += Resolver.sonatypeRepo("snapshots")

// resolvers += "spray repo" at "http://repo.spray.io"

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.2")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.7.1")

// addSbtPlugin("io.spray" % "sbt-twirl" % "0.6.2")

addSbtPlugin("me.lessis" % "less-sbt" % "0.1.10")

addSbtPlugin("me.lessis" % "coffeescripted-sbt" % "0.2.3")

// addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.9.0")

addSbtPlugin( "com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.8" )

addSbtPlugin("org.ensime" % "ensime-sbt" % "0.1.5-SNAPSHOT")
