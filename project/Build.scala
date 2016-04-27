import sbt._
import sbt.Keys._
import BuildSettings._


object DemesneBuild extends Build {
  lazy val root = Project(
    id = "root", 
    base = file( "." ) 
  ).settings (
    doNotPublishSettings:_*
  ).aggregate( core, testkit, examples )

  lazy val core = Project(
    id = "core",
    base = file( "core" )
  ).settings( defaultBuildSettings ++ publishSettings )

  lazy val testkit = Project(
    id = "testkit",
    base = file( "testkit" )
  ).settings( defaultBuildSettings ++ publishSettings )
  .dependsOn( core )

  lazy val examples = Project(
    id = "examples",
    base = file( "examples" )
  ).settings( defaultBuildSettings ++ doNotPublishSettings )
  .dependsOn( core, testkit )
}
