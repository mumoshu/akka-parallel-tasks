import sbt._
import sbt.Keys._

object ProjectBuild extends Build {

  lazy val paralleltasksexample = Project(
    id = "parallel-tasks-example",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      name := "parallel-tasks-example",
      organization := "com.github.mumoshu.akka.examples.task_queue",
      version := "0.1-SNAPSHOT",
      scalaVersion := "2.10.2",
      resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
      libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.2.1",
      libraryDependencies += "com.typesafe.akka" %% "akka-agent" % "2.2.1",
      libraryDependencies += "com.typesafe.akka" %% "akka-transactor" % "2.2.1",
      libraryDependencies += "org.specs2" %% "specs2" % "2.2" % "test",
      libraryDependencies += "org.mockito" % "mockito-all" % "1.9.0" % "test",
      libraryDependencies += "org.hamcrest" % "hamcrest-all" % "1.3" % "test"
    )
  )
}
