name := "akka-callcenter"

version := "1.0"

scalaVersion := "2.10.2"

libraryDependencies ++=
    "com.typesafe.akka" %% "akka-actor" % "2.2.1" ::
    "com.typesafe.akka" %% "akka-testkit" % "2.2.1" % "test" ::
    "com.typesafe.akka" %% "akka-remote" % "2.2.1" :: 
    "org.scalatest" %% "scalatest" % "2.0.M5b" % "test" ::
    Nil
