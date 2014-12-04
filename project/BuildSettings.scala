import sbt._
import Keys._
import CoverallsPlugin.CoverallsKeys._
import scoverage.ScoverageSbtPlugin.instrumentSettings
import org.scoverage.coveralls.CoverallsPlugin.coverallsSettings

object BuildSettings {

  val VERSION = "0.5.0-SNAPSHOT"

  lazy val noPublishing = Seq(
    publish := (),
    publishLocal := ()
  )

  lazy val basicSettings = Seq(
    version := VERSION,
    organization := "com.github.dmrolfs",
    description := "",
    startYear := Some(2013),
    licenses := Seq("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    scalaVersion := "2.11.4",
    resolvers ++= Dependencies.resolutionRepos,
    coverallsTokenFile := "~/.sbt/demesne-coveralls-token.txt",
    scalacOptions := Seq(
      "-encoding",
      "utf8",
      // "-Xlog-implicits",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-target:jvm-1.7",
      "-language:postfixOps",
      "-language:implicitConversions",
      "-Xlog-reflective-calls",
      "-Ywarn-adapted-args",
      "-Xfatal-warnings"
    ),
    scalacOptions in (Compile, doc) ++= Seq("-doc-root-content", baseDirectory.value+"/src/demesne.wiki"),
    testOptions in Test += Tests.Argument( "-oF" )
  )

  lazy val moduleSettings = basicSettings ++ instrumentSettings ++ coverallsSettings ++ Seq(
    version := VERSION,
    isSnapshot := true,
    publishTo := Some( Resolver.file("file", new File( Path.userHome.absolutePath + "/dev/dmrolfs.github.com/snapshots" ) ) )
  )
}
