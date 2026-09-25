ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "com.xebia.innovationday"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "core-banking-mcp",
    run / fork := true,
    run / connectInput := true,
    Compile / mainClass := Some("corebanking.Server"),
    executableScriptName := "core-banking-mcp",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-Wunused:all",
      "-Werror"
    ),
    libraryDependencies ++= Seq(
      "com.tjclp" %% "fast-mcp-scala" % "1.0.1",
      "dev.zio" %% "zio" % "2.1.26",
      "org.flywaydb" % "flyway-core" % "13.8.0",
      "org.flywaydb" % "flyway-database-postgresql" % "13.8.0",
      "org.postgresql" % "postgresql" % "42.7.13",
      "com.augustnagro" %% "magnum" % "1.3.1",
      "com.augustnagro" %% "magnumpg" % "1.3.1",
      "dev.zio" %% "zio-test" % "2.1.26" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // PingResponseSpec references corebanking.Server (for its zio-json given instance), which
    // eagerly runs the CORE_ENV guard at object initialization. Fork the test JVM and pin a
    // valid CORE_ENV so that guard never calls sys.exit(1) while running tests.
    Test / fork := true,
    Test / envVars += ("CORE_ENV" -> "mock"),
    // Several specs move the singleton system_clock row; run them serially so one spec's clock
    // advance never lands inside another spec's before/after comparison.
    Test / parallelExecution := false
  )
