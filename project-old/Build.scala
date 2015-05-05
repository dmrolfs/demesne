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
    .settings( moduleSettings: _* )
    .settings( libraryDependencies ++=
      compile( config ) ++
      compile( akkaActor ) ++
      compile( akkaPersistence ) ++
      compile( akkaAgent ) ++
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
      compile( inMemoryJournal ) ++
      test( akkaRemote ) ++
      test( akkaTestKit ) ++
      // test( scalatest ) ++
      test( inMemoryJournal ) ++
      test( mockito )
      //      test( akkaMultiNodeTestKit ) // ++
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

  lazy val testkit = Project( "testkit", file( "testkit" ) )
    .dependsOn( demesne )
    .settings( moduleSettings: _* )
    .settings( libraryDependencies ++=
      compile( config ) ++
      compile( akkaActor ) ++
      compile( akkaPersistence ) ++
      compile( akkaAgent ) ++
      compile( akkaContrib ) ++
      compile( akkaSlf4j ) ++
      compile( evoInflector ) ++
      compile( shapeless ) ++
      compile( logback ) ++
      compile( scalalogging ) ++
      Seq( pedsCommons ) ++
      Seq( pedsAkka ) ++
      compile( akkaRemote ) ++
      compile( akkaTestKit ) ++
      // compile( scalatest ) ++
      compile( mockito )
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
    .dependsOn( demesne, testkit % "test->compile" )
    .settings( moduleSettings: _* )
    .settings( libraryDependencies ++=
      compile( config ) ++
      compile( akkaActor ) ++
      compile( akkaPersistence ) ++
      compile( akkaAgent ) ++
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
      test( akkaRemote ) ++
      test( akkaTestKit ) ++
      // test( scalatest ) ++
      test( inMemoryJournal ) ++
//      test( h2Database ) ++
      test( mockito )
//      test( akkaMultiNodeTestKit )
    )
    .settings( libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-compiler" % _) )

  lazy val coveralls = Project( "aggregate", file( "aggregate" ) )
    .aggregate( demesne, testkit, examples )
    .settings( moduleSettings: _* )
}
