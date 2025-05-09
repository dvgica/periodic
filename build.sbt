inThisBuild(
  List(
    organization := "ca.dvgi",
    homepage := Some(url("https://github.com/dvgica/periodic")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    description := "A Scala library for self-updating vars and other periodic actions",
    developers := List(
      Developer(
        "dvgica",
        "David van Geest",
        "david.vangeest@gmail.com",
        url("http://dvgi.ca")
      )
    )
  )
)

val scala212Version = "2.12.20"
val scala213Version = "2.13.16"
val scala3Version = "3.3.6"
val scalaVersions =
  Seq(
    scala213Version,
    scala212Version,
    scala3Version
  )

def subproject(name: String) = {
  val fullName = s"periodic-$name"
  Project(
    id = fullName,
    base = file(fullName)
  ).settings(
    scalaVersion := scala213Version,
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % Versions.Munit % Test,
      "org.slf4j" % "slf4j-simple" % Versions.Slf4j % Test
    ),
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
  )
}

lazy val core = subproject("core")
  .settings(
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % Versions.Slf4j
    )
  )

lazy val pekkoStream = subproject("pekko-stream")
  .dependsOn(core % "test->test;compile->compile")
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-stream" % Versions.Pekko
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    pekkoStream
  )
  .settings(
    publish / skip := true,
    crossScalaVersions := Nil,
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
  )

ThisBuild / crossScalaVersions := scalaVersions
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))
ThisBuild / githubWorkflowBuildPreamble := Seq(
  WorkflowStep.Sbt(
    List("scalafmtCheckAll", "scalafmtSbtCheck"),
    name = Some("Check formatting with scalafmt")
  )
)
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")))

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    List("ci-release"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  )
)
