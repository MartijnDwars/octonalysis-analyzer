name := "octonalysis-analyzer"

version := "1.0"

scalaVersion := "2.11.8"

lazy val http4sVersion = "0.14.7"
lazy val spoofaxVersion = "2.1.0-SNAPSHOT"

// Spoofax artifacts are not published to Maven Central
resolvers += "Metaborg Release Repository" at "http://artifacts.metaborg.org/content/repositories/releases/"

resolvers += "Metaborg Snapshot Repository" at "http://artifacts.metaborg.org/content/repositories/snapshots/"

libraryDependencies ++= Seq(
  // Webserver
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,

  // Rho for matching tail of a route
  "org.http4s" %% "rho-swagger" % "0.12.0",

  // Language processing
  "org.metaborg" % "org.metaborg.core" % spoofaxVersion,
  "org.metaborg" % "org.metaborg.util" % spoofaxVersion,
  "org.metaborg" % "org.metaborg.spoofax.core" % spoofaxVersion,

  // Scala magic for Guice
  "net.codingwell" %% "scala-guice" % "4.1.0",

  // JSON processor
  "org.http4s" %% "http4s-json4s-native" % "0.14.8a",

  // Logging (scala-logging = slf4j wrapper, logback-classic = logging backend)
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "ch.qos.logback" % "logback-classic" % "1.1.7"
)
