import sbt._
import sbt.Keys._

object ParalleltasksexampleBuild extends Build {

  lazy val paralleltasksexample = Project(
    id = "parallel-tasks-example",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      name := "parallel-tasks-example",
      organization := "org.example",
      version := "0.1-SNAPSHOT",
      scalaVersion := "2.9.2",
      resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
      libraryDependencies += "com.typesafe.akka" % "akka-actor" % "2.0.1",
      libraryDependencies += "com.typesafe.akka" % "akka-agent" % "2.0.1"
    )
  )
}
