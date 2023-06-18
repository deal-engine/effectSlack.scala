ThisBuild / tlBaseVersion := "0.0"

ThisBuild / licenses := Seq(
  "MPL-2.0" -> url("https://www.mozilla.org/media/MPL/2.0/index.f75d2927d3c1.txt")
)
ThisBuild / developers := List(
  tlGitHubDev("ivanmoreau", "Iván Molina Rebolledo")
)

wartremoverErrors ++= Warts.unsafe

val Scala3Version = "3.2.2"
val Scala2_13Version = "2.13.10"
val slackVersion = "1.29.1"

ThisBuild / crossScalaVersions := Seq(Scala3Version, Scala2_13Version)
ThisBuild / scalaVersion := Scala3Version

lazy val root = tlCrossRootProject.aggregate(slack)

lazy val slack = crossProject(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("slack"))
  .settings(
    name := "slack",
    scalacOptions := Seq(
      "-Ykind-projector:underscores"
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"                   % "3.5.0",
      "co.fs2"        %% "fs2-core"                      % "3.7.0",
      "com.slack.api" %  "slack-api-client"              % s"${slackVersion}",
      "com.slack.api" %  "bolt-socket-mode"              % s"${slackVersion}",
      "org.java-websocket" % "Java-WebSocket"            % "1.5.3",
      // ivy"org.slf4j:slf4j-api:2.0.7"
      // ivy"org.slf4j:slf4j-api:2.0.7",
      //    ivy"org.apache.logging.log4j:log4j-api:2.20.0",
      //    ivy"org.apache.logging.log4j:log4j-core:2.20.0")
      "org.slf4j" % "slf4j-api" % "2.0.7",
      "org.apache.logging.log4j" % "log4j-api" % "2.20.0",
      "org.apache.logging.log4j" % "log4j-core" % "2.20.0",
      // RUNTIME : ivy"org.slf4j:slf4j-log4j12:2.0.7"
      "org.slf4j" % "slf4j-log4j12" % "2.0.7" % Runtime,
      "org.scalatest" %% "scalatest"                     % "3.2.16" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0"  % Test
    )
  )