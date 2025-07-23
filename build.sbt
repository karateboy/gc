name := """gc"""

version := "2.0.10"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, LauncherJarPlugin, JavaAppPackaging, WindowsPlugin)

scalaVersion := "2.11.12"

libraryDependencies ++= Seq(
  cache,
  ws,
  filters,
   "commons-io" % "commons-io" % "2.6"
)

libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.3.6"

// https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml
libraryDependencies += "org.apache.poi" % "poi-ooxml" % "5.0.0"

// https://mvnrepository.com/artifact/io.github.java-native/jssc
libraryDependencies += "io.github.java-native" % "jssc" % "2.10.2"


// https://mvnrepository.com/artifact/com.github.nscala-time/nscala-time
libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.28.0"

// https://mvnrepository.com/artifact/org.mongodb.scala/mongo-scala-driver
libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "4.2.0"

libraryDependencies += "com.github.s7connector" % "s7connector" % "2.1"


mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))

mappings in Universal ++=
(baseDirectory.value / "export/" * "*" get) map
    (x => x -> ("export/" + x.getName))

mappings in Universal ++= Seq((baseDirectory.value / "cleanup.bat", "cleanup.bat"))
routesGenerator := InjectedRoutesGenerator