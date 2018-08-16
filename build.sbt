import Dependencies._
import BuildSettings._

name := "demesne"

version in ThisBuild := "2.3.0"

organization in ThisBuild := "com.github.dmrolfs"

lazy val root = ( project in file(".") )
  .enablePlugins( BuildInfoPlugin )
  .settings( doNotPublish )
  .aggregate( core, testkit, examples )

lazy val core = ( project in file("./core") )
  .settings( defaultSettings )

lazy val testkit= ( project in file("./testkit") )
  .settings( defaultSettings )
  .dependsOn( core )

lazy val examples= ( project in file("./examples") )
  .settings( defaultSettings ++ doNotPublish )
  .dependsOn( core, testkit )


scalafmtOnCompile in ThisBuild := true

licenses := ("MIT", url("http://opensource.org/licenses/MIT")) :: Nil // this is required! otherwise Bintray will reject the code

credentials := List(Path.userHome / ".bintray" / ".jfrog-oss").filter(_.exists).map(Credentials(_))

resolvers += Resolver.url("omen bintray resolver", url("http://dl.bintray.com/omen/maven"))(Resolver.ivyStylePatterns)

publishMavenStyle := true

publishTo in ThisBuild := {
  if ( isSnapshot.value ) {
    Some("Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local")
  } else {
    Some("Bintray API Realm" at "http://api.bintray.com")
  }
}


pomExtra := {
  <scm>
    <url>https://github.com</url>
    <connection>https://github.com/dmrolfs/demesne.git</connection>
  </scm>
    <developers>
      <developer>
        <id>dmrolfs</id>
        <name>Damon Rolfs</name>
        <url>http://dmrolfs.github.io/</url>
      </developer>
    </developers>
}
