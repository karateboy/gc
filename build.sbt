name := """gc"""

version := "1.0.1"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.12"

libraryDependencies ++= Seq(
  cache,
  ws,
  filters,
  specs2 % Test,
  "com.github.nscala-time" %% "nscala-time" % "2.22.0",
  "org.mongodb.scala" %% "mongo-scala-driver" % "2.7.0",
   "commons-io" % "commons-io" % "2.6"
)

libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.3.6"

mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))

mappings in Universal ++=
(baseDirectory.value / "importEPA/backup/" * "*" get) map
    (x => x -> ("importEPA/backup/" + x.getName))

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
//routesGenerator := InjectedRoutesGenerator

scalacOptions ++= Seq("-feature")

fork in run := false