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
    scalaVersion := scala213Version,
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % Versions.Munit % Test,
      "org.slf4j" % "slf4j-simple" % Versions.Slf4j % Test
    )
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
    ),
    // A dispatcher shutdown outlives the test run and, once sbt has released
    // the test class loader, reloading a pekko class violates a loader
    // constraint and takes the build down. Forking keeps those threads out of
    // sbt's JVM.
    Test / fork := true
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    pekkoStream
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
