inThisBuild(
  List(
    organization := "ca.dvgi",
    homepage := Some(uri("https://github.com/dvgica/periodic")),
    licenses := List("Apache-2.0" -> uri("http://www.apache.org/licenses/LICENSE-2.0")),
    description := "A Scala library for self-updating vars and other periodic actions",
    developers := List(
      Developer(
        "dvgica",
        "David van Geest",
        "david.vangeest@gmail.com",
        uri("http://dvgi.ca")
      )
    )
  )
)

val scala212Version = "2.12.21"
val scala213Version = "2.13.18"
val scala3Version = "3.3.8"
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
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-simple" % Versions.Slf4j % Test
    )
  )
}

lazy val core = subproject("core")
  .settings(
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % Versions.Slf4j,
      "org.scalameta" %% "munit" % Versions.Munit % Test
    )
  )

lazy val pekkoStream = subproject("pekko-stream")
  .dependsOn(core % "test->test;compile->compile")
  .settings(
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-stream" % Versions.Pekko,
      "org.scalameta" %% "munit" % Versions.Munit % Test
    )
    // A dispatcher shutdown outlives the test run and, once sbt has released
    // the test class loader, reloading a pekko class violates a loader
    // constraint and takes the build down. Forking keeps those threads out of
    // sbt's JVM.
    Test / fork := true
  )

lazy val ox = subproject("ox")
  .dependsOn(core)
  .settings(
    crossScalaVersions := Seq(scala3Version),
    libraryDependencies ++= Seq(
      "com.softwaremill.ox" %% "core" % Versions.Ox,
      "org.slf4j" % "slf4j-simple" % Versions.Slf4j,
      "org.scalameta" %% "munit" % Versions.Munit % Test
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    pekkoStream,
    ox
  )
  .settings(
    publish / skip := true,
    crossScalaVersions := Nil
  )

ThisBuild / crossScalaVersions := scalaVersions
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / githubWorkflowBuildPreamble := Seq(
  WorkflowStep.Sbt(
    List("scalafmtCheckAll", "scalafmtSbtCheck"),
    name = Some("Check formatting with scalafmt")
  )
)
ThisBuild / githubWorkflowTargetTags := Seq("v*")
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

// sbt 2 puts targets under target/out/jvm/scala-<version>/, so the generated
// artifact upload steps embed whichever Scala version is active. That makes
// githubWorkflowCheck fail in every cross-build job but one.
ThisBuild / githubWorkflowArtifactUpload := false

// In sbt 2, `test` only runs tests that failed before, were not run, or whose
// dependencies changed. Its results are cached in ~/.cache/sbt, which CI
// restores across runs, so a job can report success having run nothing.
// `testFull` runs every test.
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("testFull"), name = Some("Build project"))
)
