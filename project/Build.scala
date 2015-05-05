import org.allenai.sbt.release.{ AllenaiReleasePlugin => ReleasePlugin }

import sbt.Keys._
import sbt._
import spray.revolver.RevolverPlugin._

import BuildSettings._


object DemesneBuild extends Build {
  lazy val root = Project(
    id = "demesne-root", 
    base = file( "." ) 
  ).settings (
    publish := { },
    publishTo := Some("bogus" at "http://nowhere.com"),
    publishLocal := { }
  ).aggregate( core ).enablePlugins( ReleasePlugin )

  lazy val core = Project(
    id = "core",
    base = file( "core" ),
    settings = defaultBuildSettings
  ).enablePlugins( ReleasePlugin )

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
