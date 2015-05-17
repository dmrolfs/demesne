import sbt.Keys._
import sbt._
import spray.revolver.RevolverPlugin._

import BuildSettings._


object DemesneBuild extends Build {
  lazy val root = Project(
    id = "root", 
    base = file( "." ) 
  ).settings (
    publish := { },
    publishTo := Some("bogus" at "http://nowhere.com"),
    publishLocal := { }
  ).aggregate( core )

  lazy val core = Project(
    id = "core",
    base = file( "core" ),
    settings = defaultBuildSettings
  )

  lazy val testkit = Project(
    id = "testkit",
    base = file( "testkit" ),
    settings = defaultBuildSettings
  ) dependsOn( core )

  lazy val examples = Project(
    id = "examples",
    base = file( "examples" ),
    settings = defaultBuildSettings
  ) dependsOn( core, testkit )
}
