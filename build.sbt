name := """gc"""

version := "1.2.6"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.12"

libraryDependencies ++= Seq(
  cache,
  ws,
  filters,
  specs2 % Test,
   "commons-io" % "commons-io" % "2.6"
)

libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.3.6"

libraryDependencies += "org.apache.poi" % "poi" % "4.1.0"
libraryDependencies += "org.apache.poi" % "poi-ooxml" % "4.1.0"
libraryDependencies += "org.apache.poi" % "poi-ooxml-schemas" % "4.1.0"
libraryDependencies += "org.apache.poi" % "poi-scratchpad" % "4.1.0"

libraryDependencies += "org.scream3r" % "jssc" % "2.8.0"
libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.22.0"
libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "2.7.0"
libraryDependencies += "com.github.s7connector" % "s7connector" % "2.1"


mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))

mappings in Universal ++=
(baseDirectory.value / "export/" * "*" get) map
    (x => x -> ("export/" + x.getName))

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
//routesGenerator := InjectedRoutesGenerator

scalacOptions ++= Seq("-feature")

fork in run := false