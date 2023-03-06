name := """gc"""

version := "1.3.7"

lazy val root = (project in file(".")).enablePlugins(PlayScala, LauncherJarPlugin)

scalaVersion := "2.11.11"

libraryDependencies ++= Seq(
  cache,
  ws,
  filters,
   "commons-io" % "commons-io" % "2.6"
)

libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.3.6"

// https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml
libraryDependencies += "org.apache.poi" % "poi-ooxml" % "5.0.0"

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


PlayKeys.fileWatchService := play.runsupport.FileWatchService.sbt(2000)

scalacOptions ++= Seq(
  "-feature",
  "-deprecation"
)
