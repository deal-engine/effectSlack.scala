ThisBuild / tlBaseVersion := "0.1"

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
ThisBuild / scalaVersion := Scala2_13Version

lazy val root = tlCrossRootProject.aggregate(slack)

lazy val slack = crossProject(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("slack"))
  .settings(
    name := "slack",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"                   % "3.5.0",
      "co.fs2"        %% "fs2-core"                      % "3.7.0",
      "com.slack.api" %  "slack-api-client"              % s"${slackVersion}",
      "com.slack.api" %  "bolt-socket-mode"              % s"${slackVersion}",
      "org.java-websocket" % "Java-WebSocket"            % "1.5.3",
    )
  )