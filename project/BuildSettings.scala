import sbt.Keys._
import sbt._

import spray.revolver.RevolverPlugin._

object BuildSettings {
  val VERSION = "0.8.0-SNAPSHOT"

  val defaultBuildSettings = Defaults.defaultSettings ++ Format.settings ++ Revolver.settings ++
    Seq(
      version := VERSION,
      organization := "com.github.dmrolfs",
      crossScalaVersions := Seq( "2.11.7" ),
      scalaVersion <<= crossScalaVersions { (vs: Seq[String]) => vs.head },
      // updateOptions := updateOptions.value.withCachedResolution(true),
      scalacOptions ++= Seq(
        // "-encoding", 
        // "utf8",
        "-target:jvm-1.7",
        "-feature",
        "-unchecked",
        "-deprecation",
        "-language:implicitConversions",
        // "-language:postfixOps",
        // "-Xlog-implicits",
        "-Xlog-reflective-calls",
        // "-Ywarn-adapted-args",
        "-Xfatal-warnings"
      ),
      javacOptions ++= Seq( "-source", "1.7", "-target", "1.7" ),
      homepage := Some( url("http://github.com/dmrolfs/demesne") ),
      licenses := Seq("Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
      conflictManager := ConflictManager.latestRevision,
      dependencyOverrides := Dependencies.defaultDependencyOverrides,

      // AllenAi Public Resolver
      resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
      // resolvers += "AllenAI Releases" at "http://utility.allenai.org:8081/nexus/content/repositories/public-releases",
      resolvers += "dmrolfs" at "http://dmrolfs.github.com/snapshots",
      resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven",
      resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven",
      // Factorie Resolver
      resolvers += "IESL Releases" at "http://dev-iesl.cs.umass.edu/nexus/content/groups/public",
      resolvers += "spray repo" at "http://repo.spray.io",
      resolvers += "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases",
      resolvers += "eaio releases" at "http://eaio.com/maven2",
      resolvers += "Sonatype OSS Releases"  at "http://oss.sonatype.org/content/repositories/releases/",
      // resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
      resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
      resolvers += "bintray-allenai-sbt-plugin-releases" at "http://dl.bintray.com/content/allenai/sbt-plugins",
      resolvers += Resolver.sonatypeRepo( "snapshots" ),
      // resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/stew/snapshots",
      resolvers += Classpaths.sbtPluginReleases,

      // SLF4J initializes itself upon the first logging call.  Because sbt
      // runs tests in parallel it is likely that a second thread will
      // invoke a second logging call before SLF4J has completed
      // initialization from the first thread's logging call, leading to
      // these messages:
      //   SLF4J: The following loggers will not work because they were created
      //   SLF4J: during the default configuration phase of the underlying logging system.
      //   SLF4J: See also http://www.slf4j.org/codes.html#substituteLogger
      //   SLF4J: com.imageworks.common.concurrent.SingleThreadInfiniteLoopRunner
      //
      // As a workaround, load SLF4J's root logger before starting the unit
      // tests [1].
      //
      // [1] http://stackoverflow.com/a/12095245
      testOptions in Test += Tests.Setup( classLoader =>
        classLoader
          .loadClass( "org.slf4j.LoggerFactory" )
          .getMethod( "getLogger", classLoader.loadClass("java.lang.String") )
          .invoke( null, "ROOT" )
      ),

      publishTo <<= version { (v: String) =>
        val disposition = if ( v.trim.endsWith("SNAPSHOT") ) "snapshots" else "releases"
        Some( Resolver.file("file", new File( Path.userHome.absolutePath + s"/jd/dev/dmrolfs.github.com/${disposition}" ) ) )
        // if ( v.trim.endsWith("SNAPSHOT") ) Some( "snapshots" at nexus + "snapshots" )
        // else Some( "releases" at nexus + "releases" )
        // val nexus = "http://utility.allenai.org:8081/nexus/content/repositories/"
        // val nexus = "http://utility.allenai.org:8081/nexus/content/repositories/"
      }
    )
}
