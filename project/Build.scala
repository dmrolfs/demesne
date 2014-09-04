import sbt._
import Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm


object Build extends Build {

  import BuildSettings._
  import Dependencies._

  // lazy val root = Project( "root", file( "." ) )
  //   .aggregate( demesne, examples )
  //   .settings( basicSettings: _* )
  //   .settings( noPublishing: _* )

  lazy val demesne = Project( "demesne", file( "." ) )
    .settings( basicSettings: _* )
    .settings( libraryDependencies ++=
      compile( config ) ++
      compile( akkaActor ) ++
      compile( akkaPersistence ) ++
      compile( akkaContrib ) ++
      compile( akkaSlf4j ) ++
      compile( evoInflector ) ++
      // compile( squants ) ++
      compile( shapeless ) ++
      compile( logback ) ++
      // compile( reactiveMongo ) ++
      compile( scalalogging ) ++
      // compile( accord ) ++
      // compile( joda ) ++
      // compile( jodaConvert ) ++
      // compile( scalaTime ) ++
      // compile( sprayHttp ) ++
      Seq( pedsCommons ) ++
      Seq( pedsAkka ) ++
      // Seq( pedsArchetype ) ++
      // test( scalatest ) ++
      test( akkaTestKit ) ++
      test( specs2 ) ++ 
      test( akkaRemote ) ++
      test( akkaMultiNodeTestKit ) // ++
      // test( commonsLogging )
    )
    .settings( libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-compiler" % _) )
    .configs( MultiJvm )
    // .settings( 
    //   compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    //   parallelExecution in Test := false,
    //   executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
    //     case (testResults, multiNodeResults) => {
    //       val overall = (
    //         if ( testResults.overall.id < multiNodeResults.overall.id ) multiNodeResults.overall
    //         else testResults.overall
    //       )

    //       Tests.Output(
    //         overall,
    //         testResults.events ++ multiNodeResults.events,
    //         testResults.summaries ++ multiNodeResults.summaries
    //       )
    //     }
    //   }
    // ) configs ( MultiJvm )

  lazy val examples = Project( "examples", file( "examples" ) )
    .dependsOn( demesne )
    .settings( basicSettings: _* )
    .settings( libraryDependencies ++=
      compile( config ) ++
      compile( akkaActor ) ++
      compile( akkaPersistence ) ++
      compile( akkaContrib ) ++
      compile( akkaSlf4j ) ++
      compile( evoInflector ) ++
      compile( squants ) ++
      compile( shapeless ) ++
      compile( logback ) ++
      compile( scalalogging ) ++
      compile( accord ) ++
      compile( joda ) ++
      compile( jodaConvert ) ++
      compile( scalaTime ) ++
      Seq( pedsCommons ) ++
      Seq( pedsAkka ) ++
      // test( scalatest ) ++
      test( akkaTestKit ) ++
      test( specs2 ) ++ 
      test( akkaRemote ) ++
      test( akkaMultiNodeTestKit )
    )
    .settings( libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-compiler" % _) )
}
