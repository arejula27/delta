/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// scalastyle:off line.size.limit

import java.nio.file.Files
import Mima._
import Unidoc._

// Scala versions
val scala212 = "2.12.17"
val scala213 = "2.13.8"
val all_scala_versions = Seq(scala212, scala213)

// Due to how publishArtifact is determined for javaOnlyReleaseSettings, incl. storage
// It was necessary to change default_scala_version to scala213 in build.sbt
// to build the project with Scala 2.13 only
// As a setting, it's possible to set it on command line easily
// sbt 'set default_scala_version := 2.13.8' [commands]
// FIXME Why not use scalaVersion?
val default_scala_version = settingKey[String]("Default Scala version")
Global / default_scala_version := scala212

// Dependent library versions
val sparkVersion = "3.5.0"
val flinkVersion = "1.16.1"
val hadoopVersion = "3.3.1"
val scalaTestVersion = "3.2.15"
val scalaTestVersionForConnectors = "3.0.8"
val parquet4sVersion = "1.9.4"

// Versions for Hive 3
val hadoopVersionForHive3 = "3.1.0"
val hiveVersion = "3.1.2"
val tezVersion = "0.9.2"

// Versions for Hive 2
val hadoopVersionForHive2 = "2.7.2"
val hive2Version = "2.3.3"
val tezVersionForHive2 = "0.8.4"

scalaVersion := default_scala_version.value

// crossScalaVersions must be set to Nil on the root project
crossScalaVersions := Nil

// For Java 11 use the following on command line
// sbt 'set targetJvm := "11"' [commands]
val targetJvm = settingKey[String]("Target JVM version")
Global / targetJvm := "1.8"

lazy val commonSettings = Seq(
  organization := "io.delta",
  scalaVersion := default_scala_version.value,
  crossScalaVersions := all_scala_versions,
  fork := true,
  scalacOptions ++= Seq(s"-target:jvm-${targetJvm.value}", "-Ywarn-unused:imports"),
  javacOptions ++= Seq("-source", targetJvm.value),
  // -target cannot be passed as a parameter to javadoc. See https://github.com/sbt/sbt/issues/355
  Compile / compile / javacOptions ++= Seq("-target", targetJvm.value),

  // Make sure any tests in any project that uses Spark is configured for running well locally
  Test / javaOptions ++= Seq(
    "-Dspark.ui.enabled=false",
    "-Dspark.ui.showConsoleProgress=false",
    "-Dspark.databricks.delta.snapshotPartitions=2",
    "-Dspark.sql.shuffle.partitions=5",
    "-Ddelta.log.cacheSize=3",
    "-Dspark.sql.sources.parallelPartitionDiscovery.parallelism=5",
    "-Xmx1024m"
  ),

  testOptions += Tests.Argument("-oF"),

  // Unidoc settings: by default dont document any source file
  unidocSourceFilePatterns := Nil,
)

lazy val spark = (project in file("spark"))
  .dependsOn(storage)
  .enablePlugins(Antlr4Plugin)
  .settings (
    name := "delta-spark",
    commonSettings,
    scalaStyleSettings,
    sparkMimaSettings,
    releaseSettings,
    libraryDependencies ++= Seq(
      // Adding test classifier seems to break transitive resolution of the core dependencies
      "org.apache.spark" %% "spark-hive" % sparkVersion % "provided",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "provided",
      "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
      "org.apache.spark" %% "spark-catalyst" % sparkVersion % "provided",

      // Test deps
      "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
      "org.scalatestplus" %% "scalacheck-1-15" % "3.2.9.0" % "test",
      "junit" % "junit" % "4.12" % "test",
      "com.novocode" % "junit-interface" % "0.11" % "test",
      "org.apache.spark" %% "spark-catalyst" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-hive" % sparkVersion % "test" classifier "tests",
    ),
    // For adding staged Spark RC versions, Ex:
    // resolvers += "Apche Spark 3.5.0 (RC1) Staging" at "https://repository.apache.org/content/repositories/orgapachespark-1444/",
    Compile / packageBin / mappings := (Compile / packageBin / mappings).value ++
        listPythonFiles(baseDirectory.value.getParentFile / "python"),

    Antlr4 / antlr4Version:= "4.9.3",
    Antlr4 / antlr4PackageName := Some("io.delta.sql.parser"),
    Antlr4 / antlr4GenListener := true,
    Antlr4 / antlr4GenVisitor := true,

    Test / testOptions += Tests.Argument("-oDF"),
    Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),

    // Don't execute in parallel since we can't have multiple Sparks in the same JVM
    Test / parallelExecution := false,

    scalacOptions ++= Seq(
      "-P:genjavadoc:strictVisibility=true" // hide package private types and methods in javadoc
    ),

    javaOptions += "-Xmx1024m",

    // Configurations to speed up tests and reduce memory footprint
    Test / javaOptions ++= Seq(
      "-Dspark.ui.enabled=false",
      "-Dspark.ui.showConsoleProgress=false",
      "-Dspark.databricks.delta.snapshotPartitions=2",
      "-Dspark.sql.shuffle.partitions=5",
      "-Ddelta.log.cacheSize=3",
      "-Dspark.sql.sources.parallelPartitionDiscovery.parallelism=5",
      "-Xmx1024m"
    ),

    // Required for testing table features see https://github.com/delta-io/delta/issues/1602
    Test / envVars += ("DELTA_TESTING", "1"),

    // Hack to avoid errors related to missing repo-root/target/scala-2.12/classes/
    createTargetClassesDir := {
      val dir = baseDirectory.value.getParentFile / "target" / "scala-2.12" / "classes"
      Files.createDirectories(dir.toPath)
    },
    Compile / compile := ((Compile / compile) dependsOn createTargetClassesDir).value,
    // Generate the package object to provide the version information in runtime.
    Compile / sourceGenerators += Def.task {
      val file = (Compile / sourceManaged).value / "io" / "delta" / "package.scala"
      IO.write(file,
        s"""package io
           |
           |package object delta {
           |  val VERSION = "${version.value}"
           |}
           |""".stripMargin)
      Seq(file)
    },
    TestParallelization.settings,

    // Unidoc settings
    unidocSourceFilePatterns := Seq(SourceFilePattern("io/delta/tables/", "io/delta/exceptions/")),
  )
  .configureUnidoc(generateScalaDoc = true)

lazy val contribs = (project in file("contribs"))
  .dependsOn(spark % "compile->compile;test->test;provided->provided")
  .settings (
    name := "delta-contribs",
    commonSettings,
    scalaStyleSettings,
    releaseSettings,
    Compile / packageBin / mappings := (Compile / packageBin / mappings).value ++
      listPythonFiles(baseDirectory.value.getParentFile / "python"),

    Test / testOptions += Tests.Argument("-oDF"),
    Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),

    // Don't execute in parallel since we can't have multiple Sparks in the same JVM
    Test / parallelExecution := false,

    javaOptions += "-Xmx1024m",

    // Configurations to speed up tests and reduce memory footprint
    Test / javaOptions ++= Seq(
      "-Dspark.ui.enabled=false",
      "-Dspark.ui.showConsoleProgress=false",
      "-Dspark.databricks.delta.snapshotPartitions=2",
      "-Dspark.sql.shuffle.partitions=5",
      "-Ddelta.log.cacheSize=3",
      "-Dspark.sql.sources.parallelPartitionDiscovery.parallelism=5",
      "-Xmx1024m"
    ),

    // Hack to avoid errors related to missing repo-root/target/scala-2.12/classes/
    createTargetClassesDir := {
      val dir = baseDirectory.value.getParentFile / "target" / "scala-2.12" / "classes"
      Files.createDirectories(dir.toPath)
    },
    Compile / compile := ((Compile / compile) dependsOn createTargetClassesDir).value
  ).configureUnidoc()

lazy val sharing = (project in file("sharing"))
  .dependsOn(spark % "compile->compile;test->test;provided->provided")
  .settings(
    name := "delta-sharing-spark",
    commonSettings,
    scalaStyleSettings,
    releaseSettings,
    Test / javaOptions ++= Seq("-ea"),
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % sparkVersion % "provided",

      "io.delta" %% "delta-sharing-client" % "1.0.4",

      // Test deps
      "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
      "org.scalatestplus" %% "scalacheck-1-15" % "3.2.9.0" % "test",
      "junit" % "junit" % "4.12" % "test",
      "com.novocode" % "junit-interface" % "0.11" % "test",
      "org.apache.spark" %% "spark-catalyst" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-hive" % sparkVersion % "test" classifier "tests",
    )
  ).configureUnidoc()

lazy val kernelApi = (project in file("kernel/kernel-api"))
  .settings(
    name := "delta-kernel-api",
    commonSettings,
    scalaStyleSettings,
    javaOnlyReleaseSettings,
    Test / javaOptions ++= Seq("-ea"),
    libraryDependencies ++= Seq(
      "org.roaringbitmap" % "RoaringBitmap" % "0.9.25",
      "org.slf4j" % "slf4j-api" % "1.7.36",

      "com.fasterxml.jackson.core" % "jackson-databind" % "2.13.5" % "test",
      "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
      "junit" % "junit" % "4.13" % "test",
      "com.novocode" % "junit-interface" % "0.11" % "test",
      "org.slf4j" % "slf4j-log4j12" % "1.7.36" % "test"
    ),
    javaCheckstyleSettings("kernel/dev/checkstyle.xml"),
    // Unidoc settings
    unidocSourceFilePatterns := Seq(SourceFilePattern("io/delta/kernel/")),
  ).configureUnidoc(docTitle = "Delta Kernel")

lazy val kernelDefaults = (project in file("kernel/kernel-defaults"))
  .dependsOn(kernelApi)
  .dependsOn(spark % "test->test")
  .dependsOn(goldenTables % "test")
  .settings(
    name := "delta-kernel-defaults",
    commonSettings,
    scalaStyleSettings,
    javaOnlyReleaseSettings,
    Test / javaOptions ++= Seq("-ea"),
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client-runtime" % hadoopVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.13.5",
      "org.apache.parquet" % "parquet-hadoop" % "1.12.3",

      "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
      "junit" % "junit" % "4.13" % "test",
      "commons-io" % "commons-io" % "2.8.0" % "test",
      "com.novocode" % "junit-interface" % "0.11" % "test",
      "org.slf4j" % "slf4j-log4j12" % "1.7.36" % "test",

      "org.apache.spark" %% "spark-hive" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-catalyst" % sparkVersion % "test" classifier "tests",
    ),
    javaCheckstyleSettings("kernel/dev/checkstyle.xml"),
      // Unidoc settings
    unidocSourceFilePatterns += SourceFilePattern("io/delta/kernel/"),
  ).configureUnidoc(docTitle = "Delta Kernel Defaults")

// TODO javastyle tests
// TODO unidoc
// TODO(scott): figure out a better way to include tests in this project
lazy val storage = (project in file("storage"))
  .settings (
    name := "delta-storage",
    commonSettings,
    javaOnlyReleaseSettings,
    libraryDependencies ++= Seq(
      // User can provide any 2.x or 3.x version. We don't use any new fancy APIs. Watch out for
      // versions with known vulnerabilities.
      "org.apache.hadoop" % "hadoop-common" % hadoopVersion % "provided",

      // Note that the org.apache.hadoop.fs.s3a.Listing::createFileStatusListingIterator 3.3.1 API
      // is not compatible with 3.3.2.
      "org.apache.hadoop" % "hadoop-aws" % hadoopVersion % "provided",

      // Test Deps
      "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
    ),

    // Unidoc settings
    unidocSourceFilePatterns += SourceFilePattern("/LogStore.java", "/CloseableIterator.java"),
  ).configureUnidoc()

lazy val storageS3DynamoDB = (project in file("storage-s3-dynamodb"))
  .dependsOn(storage % "compile->compile;test->test;provided->provided")
  .dependsOn(spark % "test->test")
  .settings (
    name := "delta-storage-s3-dynamodb",
    commonSettings,
    javaOnlyReleaseSettings,

    // uncomment only when testing FailingS3DynamoDBLogStore. this will include test sources in
    // a separate test jar.
    // Test / publishArtifact := true,

    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-java-sdk" % "1.7.4" % "provided",

      // Test Deps
      "org.apache.hadoop" % "hadoop-aws" % hadoopVersion % "test", // RemoteFileChangedException
    )
  ).configureUnidoc()

val icebergSparkRuntimeArtifactName = {
 val (expMaj, expMin, _) = getMajorMinorPatch(sparkVersion)
 s"iceberg-spark-runtime-$expMaj.$expMin"
}

lazy val testDeltaIcebergJar = (project in file("testDeltaIcebergJar"))
  // delta-iceberg depends on delta-spark! So, we need to include it during our test.
  .dependsOn(spark % "test")
  .settings(
    name := "test-delta-iceberg-jar",
    commonSettings,
    skipReleaseSettings,
    exportJars := true,
    Compile / unmanagedJars += (iceberg / assembly).value,
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersion,
      "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
      "org.apache.spark" %% "spark-core" % sparkVersion % "test"
    )
  )

val deltaIcebergSparkIncludePrefixes = Seq(
  // We want everything from this package
  "org/apache/spark/sql/delta/icebergShaded",

  // We only want the files in this project from this package. e.g. we want to exclude
  // org/apache/spark/sql/delta/commands/convert/ConvertTargetFile.class (from delta-spark project).
  "org/apache/spark/sql/delta/commands/convert/IcebergFileManifest",
  "org/apache/spark/sql/delta/commands/convert/IcebergSchemaUtils",
  "org/apache/spark/sql/delta/commands/convert/IcebergTable"
)

// Build using: build/sbt clean icebergShaded/compile iceberg/compile
// It will fail the first time, just re-run it.
// scalastyle:off println
lazy val iceberg = (project in file("iceberg"))
  .dependsOn(spark % "compile->compile;test->test;provided->provided")
  .settings (
    name := "delta-iceberg",
    commonSettings,
    scalaStyleSettings,
    releaseSettings,
    libraryDependencies ++= Seq(
      // Fix Iceberg's legacy java.lang.NoClassDefFoundError: scala/jdk/CollectionConverters$ error
      // due to legacy scala.
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1",
      "org.apache.iceberg" %% icebergSparkRuntimeArtifactName % "1.4.0" % "provided",
      "com.github.ben-manes.caffeine" % "caffeine" % "2.9.3"
    ),
    Compile / unmanagedJars += (icebergShaded / assembly).value,
    // Generate the assembly JAR as the package JAR
    Compile / packageBin := assembly.value,
    assembly / assemblyJarName := s"${name.value}_${scalaBinaryVersion.value}-${version.value}.jar",
    assembly / logLevel := Level.Info,
    assembly / test := {},
    assembly / assemblyExcludedJars := {
      // Note: the input here is only `libraryDependencies` jars, not `.dependsOn(_)` jars.
      val allowedJars = Seq(
        s"iceberg-shaded_${scalaBinaryVersion.value}-${version.value}.jar",
        s"scala-library-${scala212}.jar",
        s"scala-library-${scala213}.jar",
        s"scala-collection-compat_${scalaBinaryVersion.value}-2.1.1.jar",
        "caffeine-2.9.3.jar",
        // Note: We are excluding
        // - antlr4-runtime-4.9.3.jar
        // - checker-qual-3.19.0.jar
        // - error_prone_annotations-2.10.0.jar
      )
      val cp = (assembly / fullClasspath).value

      // Return `true` when we want the jar `f` to be excluded from the assembly jar
      cp.filter { f =>
        val doExclude = !allowedJars.contains(f.data.getName)
        println(s"Excluding jar: ${f.data.getName} ? $doExclude")
        doExclude
      }
    },
    assembly / assemblyMergeStrategy := {
      // Project iceberg `dependsOn` spark and accidentally brings in it, along with its
      // compile-time dependencies (like delta-storage). We want these excluded from the
      // delta-iceberg jar.
      case PathList("io", "delta", xs @ _*) =>
        // - delta-storage will bring in classes: io/delta/storage
        // - delta-spark will bring in classes: io/delta/exceptions/, io/delta/implicits,
        //   io/delta/package, io/delta/sql, io/delta/tables,
        println(s"Discarding class: io/delta/${xs.mkString("/")}")
        MergeStrategy.discard
      case PathList("com", "databricks", xs @ _*) =>
        // delta-spark will bring in com/databricks/spark/util
        println(s"Discarding class: com/databricks/${xs.mkString("/")}")
        MergeStrategy.discard
      case PathList("org", "apache", "spark", xs @ _*)
        if !deltaIcebergSparkIncludePrefixes.exists { prefix =>
          s"org/apache/spark/${xs.mkString("/")}".startsWith(prefix) } =>
        println(s"Discarding class: org/apache/spark/${xs.mkString("/")}")
        MergeStrategy.discard
      case PathList("scoverage", xs @ _*) =>
        println(s"Discarding class: scoverage/${xs.mkString("/")}")
        MergeStrategy.discard
      case x =>
        println(s"Including class: $x")
        (assembly / assemblyMergeStrategy).value(x)
    },
    assemblyPackageScala / assembleArtifact := false
  )
// scalastyle:on println

lazy val generateIcebergJarsTask = TaskKey[Unit]("generateIcebergJars", "Generate Iceberg JARs")

lazy val icebergShaded = (project in file("icebergShaded"))
  .dependsOn(spark % "provided")
  .settings (
    name := "iceberg-shaded",
    commonSettings,
    skipReleaseSettings,

    // Compile, patch and generated Iceberg JARs
    generateIcebergJarsTask := {
      import sys.process._
      val scriptPath = baseDirectory.value / "generate_iceberg_jars.py"
      // Download iceberg code in `iceberg_src` dir and generate the JARs in `lib` dir
      Seq("python3", scriptPath.getPath)!
    },
    Compile / unmanagedJars := (Compile / unmanagedJars).dependsOn(generateIcebergJarsTask).value,
    cleanFiles += baseDirectory.value / "iceberg_src",
    cleanFiles += baseDirectory.value / "lib",

    // Generated shaded Iceberg JARs
    Compile / packageBin := assembly.value,
    assembly / assemblyJarName := s"${name.value}_${scalaBinaryVersion.value}-${version.value}.jar",
    assembly / logLevel := Level.Info,
    assembly / test := {},
    assembly / assemblyShadeRules := Seq(
      ShadeRule.rename("org.apache.iceberg.**" -> "shadedForDelta.@0").inAll,
    ),
    assemblyPackageScala / assembleArtifact := false,
    // Make the 'compile' invoke the 'assembly' task to generate the uber jar.
  )

lazy val hive = (project in file("connectors/hive"))
  .dependsOn(standaloneCosmetic)
  .settings (
    name := "delta-hive",
    commonSettings,
    releaseSettings,

    // Minimal dependencies to compile the codes. This project doesn't run any tests so we don't
    // need any runtime dependencies.
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersionForHive3 % "provided",
      "org.apache.hive" % "hive-exec" % hiveVersion % "provided" classifier "core",
      "org.apache.hive" % "hive-metastore" % hiveVersion % "provided"
    ),
  )

lazy val hiveAssembly = (project in file("connectors/hive-assembly"))
  .dependsOn(hive)
  .settings(
    name := "delta-hive-assembly",
    Compile / unmanagedJars += (hive / Compile / packageBin / packageBin).value,
    commonSettings,
    skipReleaseSettings,

    assembly / logLevel := Level.Info,
    assembly / assemblyJarName := s"${name.value}_${scalaBinaryVersion.value}-${version.value}.jar",
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      // Discard `module-info.class` to fix the `different file contents found` error.
      // TODO Upgrade SBT to 1.5 which will do this automatically
      case "module-info.class" => MergeStrategy.discard
      // Discard unused `parquet.thrift` so that we don't conflict the file used by the user
      case "parquet.thrift" => MergeStrategy.discard
      // Discard the jackson service configs that we don't need. These files are not shaded so
      // adding them may conflict with other jackson version used by the user.
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    },
    // Make the 'compile' invoke the 'assembly' task to generate the uber jar.
    Compile / packageBin := assembly.value
  )

lazy val hiveTest = (project in file("connectors/hive-test"))
  .dependsOn(goldenTables % "test")
  .settings (
    name := "hive-test",
    // Make the project use the assembly jar to ensure we are testing the assembly jar that users
    // will use in real environment.
    Compile / unmanagedJars += (hiveAssembly / Compile / packageBin / packageBin).value,
    commonSettings,
    skipReleaseSettings,
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersionForHive3 % "provided",
      "org.apache.hive" % "hive-exec" % hiveVersion % "provided" classifier "core" excludeAll(
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule(organization = "com.google.protobuf")
      ),
      "org.apache.hive" % "hive-metastore" % hiveVersion % "provided"  excludeAll(
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule("org.apache.hive", "hive-exec")
      ),
      "org.apache.hive" % "hive-cli" % hiveVersion % "test" excludeAll(
        ExclusionRule("ch.qos.logback", "logback-classic"),
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
        ExclusionRule("org.apache.hive", "hive-exec"),
        ExclusionRule("com.google.guava", "guava"),
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule(organization = "com.google.protobuf")
      ),
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test"
    )
  )

lazy val hiveMR = (project in file("connectors/hive-mr"))
  .dependsOn(hiveTest % "test->test")
  .settings (
    name := "hive-mr",
    commonSettings,
    skipReleaseSettings,
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersionForHive3 % "provided",
      "org.apache.hive" % "hive-exec" % hiveVersion % "provided" excludeAll(
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm")
      ),
      "org.apache.hadoop" % "hadoop-common" % hadoopVersionForHive3 % "test" classifier "tests",
      "org.apache.hadoop" % "hadoop-mapreduce-client-hs" % hadoopVersionForHive3 % "test",
      "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient" % hadoopVersionForHive3 % "test" classifier "tests",
      "org.apache.hadoop" % "hadoop-yarn-server-tests" % hadoopVersionForHive3 % "test" classifier "tests",
      "org.apache.hive" % "hive-cli" % hiveVersion % "test" excludeAll(
        ExclusionRule("ch.qos.logback", "logback-classic"),
        ExclusionRule("com.google.guava", "guava"),
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm")
      ),
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test"
    )
  )

lazy val hiveTez = (project in file("connectors/hive-tez"))
  .dependsOn(hiveTest % "test->test")
  .settings (
    name := "hive-tez",
    commonSettings,
    skipReleaseSettings,
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersionForHive3 % "provided" excludeAll (
        ExclusionRule(organization = "com.google.protobuf")
        ),
      "com.google.protobuf" % "protobuf-java" % "2.5.0",
      "org.apache.hive" % "hive-exec" % hiveVersion % "provided" classifier "core" excludeAll(
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule(organization = "com.google.protobuf")
      ),
      "org.jodd" % "jodd-core" % "3.5.2",
      "org.apache.hive" % "hive-metastore" % hiveVersion % "provided" excludeAll(
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule("org.apache.hive", "hive-exec")
      ),
      "org.apache.hadoop" % "hadoop-common" % hadoopVersionForHive3 % "test" classifier "tests",
      "org.apache.hadoop" % "hadoop-mapreduce-client-hs" % hadoopVersionForHive3 % "test",
      "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient" % hadoopVersionForHive3 % "test" classifier "tests",
      "org.apache.hadoop" % "hadoop-yarn-server-tests" % hadoopVersionForHive3 % "test" classifier "tests",
      "org.apache.hive" % "hive-cli" % hiveVersion % "test" excludeAll(
        ExclusionRule("ch.qos.logback", "logback-classic"),
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
        ExclusionRule("org.apache.hive", "hive-exec"),
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule(organization = "com.google.protobuf")
      ),
      "org.apache.hadoop" % "hadoop-yarn-common" % hadoopVersionForHive3 % "test",
      "org.apache.hadoop" % "hadoop-yarn-api" % hadoopVersionForHive3 % "test",
      "org.apache.tez" % "tez-mapreduce" % tezVersion % "test",
      "org.apache.tez" % "tez-dag" % tezVersion % "test",
      "org.apache.tez" % "tez-tests" % tezVersion % "test" classifier "tests",
      "com.esotericsoftware" % "kryo-shaded" % "4.0.2" % "test",
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test"
    )
  )


lazy val hive2MR = (project in file("connectors/hive2-mr"))
  .dependsOn(goldenTables % "test")
  .settings (
    name := "hive2-mr",
    commonSettings,
    skipReleaseSettings,
    Compile / unmanagedJars ++= Seq(
      (hiveAssembly / Compile / packageBin / packageBin).value, // delta-hive assembly
      (hiveTest / Test / packageBin / packageBin).value
    ),
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersionForHive2 % "provided",
      "org.apache.hive" % "hive-exec" % hive2Version % "provided" excludeAll(
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm")
      ),
      "org.apache.hadoop" % "hadoop-common" % hadoopVersionForHive2 % "test" classifier "tests",
      "org.apache.hadoop" % "hadoop-mapreduce-client-hs" % hadoopVersionForHive2 % "test",
      "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient" % hadoopVersionForHive2 % "test" classifier "tests",
      "org.apache.hadoop" % "hadoop-yarn-server-tests" % hadoopVersionForHive2 % "test" classifier "tests",
      "org.apache.hive" % "hive-cli" % hive2Version % "test" excludeAll(
        ExclusionRule("ch.qos.logback", "logback-classic"),
        ExclusionRule("com.google.guava", "guava"),
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm")
      ),
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test"
    )
  )

lazy val hive2Tez = (project in file("connectors/hive2-tez"))
  .dependsOn(goldenTables % "test")
  .settings (
    name := "hive2-tez",
    commonSettings,
    skipReleaseSettings,
    Compile / unmanagedJars ++= Seq(
      (hiveAssembly / Compile / packageBin / packageBin).value,
      (hiveTest / Test / packageBin / packageBin).value
    ),
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersionForHive2 % "provided" excludeAll (
        ExclusionRule(organization = "com.google.protobuf")
        ),
      "com.google.protobuf" % "protobuf-java" % "2.5.0",
      "org.apache.hive" % "hive-exec" % hive2Version % "provided" classifier "core" excludeAll(
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule(organization = "com.google.protobuf")
      ),
      "org.jodd" % "jodd-core" % "3.5.2",
      "org.apache.hive" % "hive-metastore" % hive2Version % "provided" excludeAll(
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule("org.apache.hive", "hive-exec")
      ),
      "org.apache.hadoop" % "hadoop-common" % hadoopVersionForHive2 % "test" classifier "tests",
      "org.apache.hadoop" % "hadoop-mapreduce-client-hs" % hadoopVersionForHive2 % "test",
      "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient" % hadoopVersionForHive2 % "test" classifier "tests",
      "org.apache.hadoop" % "hadoop-yarn-server-tests" % hadoopVersionForHive2 % "test" classifier "tests",
      "org.apache.hive" % "hive-cli" % hive2Version % "test" excludeAll(
        ExclusionRule("ch.qos.logback", "logback-classic"),
        ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
        ExclusionRule("org.apache.hive", "hive-exec"),
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule(organization = "com.google.protobuf")
      ),
      "org.apache.hadoop" % "hadoop-yarn-common" % hadoopVersionForHive2 % "test",
      "org.apache.hadoop" % "hadoop-yarn-api" % hadoopVersionForHive2 % "test",
      "org.apache.tez" % "tez-mapreduce" % tezVersionForHive2 % "test",
      "org.apache.tez" % "tez-dag" % tezVersionForHive2 % "test",
      "org.apache.tez" % "tez-tests" % tezVersionForHive2 % "test" classifier "tests",
      "com.esotericsoftware" % "kryo-shaded" % "4.0.2" % "test",
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test"
    )
  )

/**
 * We want to publish the `standalone` project's shaded JAR (created from the
 * build/sbt standalone/assembly command).
 *
 * However, build/sbt standalone/publish and build/sbt standalone/publishLocal will use the
 * non-shaded JAR from the build/sbt standalone/package command.
 *
 * So, we create an impostor, cosmetic project used only for publishing.
 *
 * build/sbt standaloneCosmetic/package
 * - creates connectors/standalone/target/scala-2.12/delta-standalone-original-shaded_2.12-0.2.1-SNAPSHOT.jar
 *   (this is the shaded JAR we want)
 *
 * build/sbt standaloneCosmetic/publishM2
 * - packages the shaded JAR (above) and then produces:
 * -- .m2/repository/io/delta/delta-standalone_2.12/0.2.1-SNAPSHOT/delta-standalone_2.12-0.2.1-SNAPSHOT.pom
 * -- .m2/repository/io/delta/delta-standalone_2.12/0.2.1-SNAPSHOT/delta-standalone_2.12-0.2.1-SNAPSHOT.jar
 * -- .m2/repository/io/delta/delta-standalone_2.12/0.2.1-SNAPSHOT/delta-standalone_2.12-0.2.1-SNAPSHOT-sources.jar
 * -- .m2/repository/io/delta/delta-standalone_2.12/0.2.1-SNAPSHOT/delta-standalone_2.12-0.2.1-SNAPSHOT-javadoc.jar
 */
lazy val standaloneCosmetic = project
  .dependsOn(storage) // this doesn't impact the output artifact (jar), only the pom.xml dependencies
  .settings(
    name := "delta-standalone",
    commonSettings,
    releaseSettings,
    exportJars := true,
    Compile / packageBin := (standaloneParquet / assembly).value,
    Compile / packageSrc := (standalone / Compile / packageSrc).value,
    libraryDependencies ++= scalaCollectionPar(scalaVersion.value) ++ Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersion % "provided",
      "org.apache.parquet" % "parquet-hadoop" % "1.12.3" % "provided",
      // parquet4s-core dependencies that are not shaded are added with compile scope.
      "com.chuusai" %% "shapeless" % "2.3.4",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.3"
    )
  )

lazy val testStandaloneCosmetic = (project in file("connectors/testStandaloneCosmetic"))
  .dependsOn(standaloneCosmetic)
  .dependsOn(goldenTables % "test")
  .settings(
    name := "test-standalone-cosmetic",
    commonSettings,
    skipReleaseSettings,
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersion,
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test",
    )
  )

/**
 * A test project to verify `ParquetSchemaConverter` APIs are working after the user provides
 * `parquet-hadoop`. We use a separate project because we want to test whether Delta Standlone APIs
 * except `ParquetSchemaConverter` are working without `parquet-hadoop` in testStandaloneCosmetic`.
 */
lazy val testParquetUtilsWithStandaloneCosmetic = project.dependsOn(standaloneCosmetic)
  .settings(
    name := "test-parquet-utils-with-standalone-cosmetic",
    commonSettings,
    skipReleaseSettings,
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersion,
      "org.apache.parquet" % "parquet-hadoop" % "1.12.3" % "provided",
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test",
    )
  )

def scalaCollectionPar(version: String) = version match {
  case v if v.startsWith("2.13.") =>
    Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4")
  case _ => Seq()
}

/**
 * The public API ParquetSchemaConverter exposes Parquet classes in its methods so we cannot apply
 * shading rules on it. However, sbt-assembly doesn't allow excluding a single file. Hence, we
 * create a separate project to skip the shading.
 */
lazy val standaloneParquet = (project in file("connectors/standalone-parquet"))
  .dependsOn(standaloneWithoutParquetUtils)
  .settings(
    name := "delta-standalone-parquet",
    commonSettings,
    skipReleaseSettings,
    libraryDependencies ++= Seq(
      "org.apache.parquet" % "parquet-hadoop" % "1.12.3" % "provided",
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test"
    ),
    assemblyPackageScala / assembleArtifact := false
  )

/** A dummy project to allow `standaloneParquet` depending on the shaded standalone jar. */
lazy val standaloneWithoutParquetUtils = project
  .settings(
    name := "delta-standalone-without-parquet-utils",
    commonSettings,
    skipReleaseSettings,
    exportJars := true,
    Compile / packageBin := (standalone / assembly).value
  )

// TODO scalastyle settings
lazy val standalone = (project in file("connectors/standalone"))
  .dependsOn(storage % "compile->compile;provided->provided")
  .dependsOn(goldenTables % "test")
  .settings(
    name := "delta-standalone-original",
    commonSettings,
    skipReleaseSettings,
    standaloneMimaSettings,
    // When updating any dependency here, we should also review `pomPostProcess` in project
    // `standaloneCosmetic` and update it accordingly.
    libraryDependencies ++= scalaCollectionPar(scalaVersion.value) ++ Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersion % "provided",
      "com.github.mjakubowski84" %% "parquet4s-core" % parquet4sVersion excludeAll (
        ExclusionRule("org.slf4j", "slf4j-api")
        ),
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.12.3",
      "org.json4s" %% "json4s-jackson" % "3.7.0-M11" excludeAll (
        ExclusionRule("com.fasterxml.jackson.core"),
        ExclusionRule("com.fasterxml.jackson.module")
      ),
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test",
    ),
    Compile / sourceGenerators += Def.task {
      val file = (Compile / sourceManaged).value / "io" / "delta" / "standalone" / "package.scala"
      IO.write(file,
        s"""package io.delta
           |
           |package object standalone {
           |  val VERSION = "${version.value}"
           |  val NAME = "Delta Standalone"
           |}
           |""".stripMargin)
      Seq(file)
    },

    /**
     * Standalone packaged (unshaded) jar.
     *
     * Build with `build/sbt standalone/package` command.
     * e.g. connectors/standalone/target/scala-2.12/delta-standalone-original-unshaded_2.12-0.2.1-SNAPSHOT.jar
     */
    artifactName := { (sv: ScalaVersion, module: ModuleID, artifact: Artifact) =>
      artifact.name + "-unshaded" + "_" + sv.binary + "-" + module.revision  + "." + artifact.extension
    },

    /**
     * Standalone assembly (shaded) jar. This is what we want to release.
     *
     * Build with `build/sbt standalone/assembly` command.
     * e.g. connectors/standalone/target/scala-2.12/delta-standalone-original-shaded_2.12-0.2.1-SNAPSHOT.jar
     */
    assembly / logLevel := Level.Info,
    assembly / test := {},
    assembly / assemblyJarName := s"${name.value}-shaded_${scalaBinaryVersion.value}-${version.value}.jar",
    // We exclude jars first, and then we shade what is remaining. Note: the input here is only
    // `libraryDependencies` jars, not `.dependsOn(_)` jars.
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      val allowedPrefixes = Set("META_INF", "io", "json4s", "jackson", "paranamer",
        "parquet4s", "parquet-", "audience-annotations", "commons-pool")
      cp.filter { f =>
        !allowedPrefixes.exists(prefix => f.data.getName.startsWith(prefix))
      }
    },
    assembly / assemblyShadeRules := Seq(
      ShadeRule.rename("com.fasterxml.jackson.**" -> "shadedelta.@0").inAll,
      ShadeRule.rename("com.thoughtworks.paranamer.**" -> "shadedelta.@0").inAll,
      ShadeRule.rename("org.json4s.**" -> "shadedelta.@0").inAll,
      ShadeRule.rename("com.github.mjakubowski84.parquet4s.**" -> "shadedelta.@0").inAll,
      ShadeRule.rename("org.apache.commons.pool.**" -> "shadedelta.@0").inAll,
      ShadeRule.rename("org.apache.parquet.**" -> "shadedelta.@0").inAll,
      ShadeRule.rename("shaded.parquet.**" -> "shadedelta.@0").inAll,
      ShadeRule.rename("org.apache.yetus.audience.**" -> "shadedelta.@0").inAll
    ),
    assembly / assemblyMergeStrategy := {
      // Discard `module-info.class` to fix the `different file contents found` error.
      // TODO Upgrade SBT to 1.5 which will do this automatically
      case "module-info.class" => MergeStrategy.discard
      // Discard unused `parquet.thrift` so that we don't conflict the file used by the user
      case "parquet.thrift" => MergeStrategy.discard
      // Discard the jackson service configs that we don't need. These files are not shaded so
      // adding them may conflict with other jackson version used by the user.
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.discard
      // This project `.dependsOn` delta-storage, and its classes will be included by default
      // in this assembly jar. Manually discard them since it is already a compile-time dependency.
      case PathList("io", "delta", "storage", xs @ _*) => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    assembly / artifact := {
      val art = (assembly / artifact).value
      art.withClassifier(Some("assembly"))
    },
    addArtifact(assembly / artifact, assembly),

    // Unidoc setting
    unidocSourceFilePatterns += SourceFilePattern("io/delta/standalone/"),
    javaCheckstyleSettings("connectors/dev/checkstyle.xml")
  ).configureUnidoc()


/*
TODO (TD): Tests are failing for some reason
lazy val compatibility = (project in file("connectors/oss-compatibility-tests"))
  // depend on standalone test codes as well
  .dependsOn(standalone % "compile->compile;test->test")
  .dependsOn(spark % "test -> compile")
  .settings(
    name := "compatibility",
    commonSettings,
    skipReleaseSettings,
    libraryDependencies ++= Seq(
      // Test Dependencies
      "io.netty" % "netty-buffer"  % "4.1.63.Final" % "test",
      "org.scalatest" %% "scalatest" % "3.1.0" % "test",
      "commons-io" % "commons-io" % "2.8.0" % "test",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "test",
      "org.apache.spark" %% "spark-catalyst" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "test" classifier "tests",
    )
  )
 */

lazy val goldenTables = (project in file("connectors/golden-tables"))
  .dependsOn(spark % "test") // depends on delta-spark
  .settings(
    name := "golden-tables",
    commonSettings,
    skipReleaseSettings,
    libraryDependencies ++= Seq(
      // Test Dependencies
      "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
      "commons-io" % "commons-io" % "2.8.0" % "test",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "test",
      "org.apache.spark" %% "spark-catalyst" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
      "org.apache.spark" %% "spark-sql" % sparkVersion % "test" classifier "tests"
    )
  )

def sqlDeltaImportScalaVersion(scalaBinaryVersion: String): String = {
  scalaBinaryVersion match {
    // sqlDeltaImport doesn't support 2.11. We return 2.12 so that we can resolve the dependencies
    // but we will not publish sqlDeltaImport with Scala 2.11.
    case "2.11" => "2.12"
    case _ => scalaBinaryVersion
  }
}

lazy val sqlDeltaImport = (project in file("connectors/sql-delta-import"))
  .dependsOn(spark)
  .settings (
    name := "sql-delta-import",
    commonSettings,
    skipReleaseSettings,
    publishArtifact := scalaBinaryVersion.value != "2.11",
    Test / publishArtifact := false,
    libraryDependencies ++= Seq(
      "io.netty" % "netty-buffer"  % "4.1.63.Final" % "test",
      "org.apache.spark" % ("spark-sql_" + sqlDeltaImportScalaVersion(scalaBinaryVersion.value)) % sparkVersion % "provided",
      "org.rogach" %% "scallop" % "3.5.1",
      "org.scalatest" %% "scalatest" % scalaTestVersionForConnectors % "test",
      "com.h2database" % "h2" % "1.4.200" % "test",
      "org.apache.spark" % ("spark-catalyst_" + sqlDeltaImportScalaVersion(scalaBinaryVersion.value)) % sparkVersion % "test",
      "org.apache.spark" % ("spark-core_" + sqlDeltaImportScalaVersion(scalaBinaryVersion.value)) % sparkVersion % "test",
      "org.apache.spark" % ("spark-sql_" + sqlDeltaImportScalaVersion(scalaBinaryVersion.value)) % sparkVersion % "test"
    )
  )

def flinkScalaVersion(scalaBinaryVersion: String): String = {
  scalaBinaryVersion match {
    // Flink doesn't support 2.13. We return 2.12 so that we can resolve the dependencies but we
    // will not publish Flink connector with Scala 2.13.
    case "2.13" => "2.12"
    case _ => scalaBinaryVersion
  }
}

lazy val flink = (project in file("connectors/flink"))
  .dependsOn(standaloneCosmetic % "provided")
  .dependsOn(kernelApi)
  .dependsOn(kernelDefaults)
  .settings (
    name := "delta-flink",
    commonSettings,
    releaseSettings,
    flinkMimaSettings,
    publishArtifact := scalaBinaryVersion.value == "2.12", // only publish once
    autoScalaLibrary := false, // exclude scala-library from dependencies
    Test / publishArtifact := false,
    pomExtra :=
      <url>https://github.com/delta-io/delta</url>
        <scm>
          <url>git@github.com:delta-io/delta.git</url>
          <connection>scm:git:git@github.com:delta-io/delta.git</connection>
        </scm>
        <developers>
          <developer>
            <id>pkubit-g</id>
            <name>Paweł Kubit</name>
            <url>https://github.com/pkubit-g</url>
          </developer>
          <developer>
            <id>kristoffSC</id>
            <name>Krzysztof Chmielewski</name>
            <url>https://github.com/kristoffSC</url>
          </developer>
        </developers>,
    crossPaths := false,
    libraryDependencies ++= Seq(
      "org.apache.flink" % "flink-parquet" % flinkVersion % "provided",
      "org.apache.flink" % "flink-table-common" % flinkVersion % "provided",
      "org.apache.hadoop" % "hadoop-client" % hadoopVersion % "provided",
      "org.apache.flink" % "flink-connector-files" % flinkVersion % "provided",
      "org.apache.flink" % "flink-table-runtime" % flinkVersion % "provided",
      "org.apache.flink" % "flink-scala_2.12" % flinkVersion % "provided",
      "org.apache.flink" % "flink-connector-hive_2.12" % flinkVersion % "provided",
      "org.apache.flink" % "flink-table-planner_2.12" % flinkVersion % "provided",

      "org.apache.flink" % "flink-connector-files" % flinkVersion % "test" classifier "tests",
      "org.apache.flink" % "flink-runtime-web" % flinkVersion % "test",
      "org.apache.flink" % "flink-sql-gateway-api" % flinkVersion % "test",
      "org.apache.flink" % "flink-connector-test-utils" % flinkVersion % "test",
      "org.apache.flink" % "flink-clients" % flinkVersion % "test",
      "org.apache.flink" % "flink-test-utils" % flinkVersion % "test",
      "org.apache.hadoop" % "hadoop-common" % hadoopVersion % "test" classifier "tests",
      "org.mockito" % "mockito-inline" % "4.11.0" % "test",
      "net.aichler" % "jupiter-interface" % JupiterKeys.jupiterVersion.value % Test,
      "org.junit.vintage" % "junit-vintage-engine" % "5.8.2" % "test",
      "org.mockito" % "mockito-junit-jupiter" % "4.11.0" % "test",
      "org.junit.jupiter" % "junit-jupiter-params" % "5.8.2" % "test",
      "io.github.artsok" % "rerunner-jupiter" % "2.1.6" % "test",

      // Exclusions due to conflicts with Flink's libraries from table planer, hive, calcite etc.
      "org.apache.hive" % "hive-metastore" % "3.1.2" % "test" excludeAll(
        ExclusionRule("org.apache.avro", "avro"),
        ExclusionRule("org.slf4j", "slf4j-log4j12"),
        ExclusionRule("org.pentaho"),
        ExclusionRule("org.apache.hbase"),
        ExclusionRule("org.apache.hbase"),
        ExclusionRule("co.cask.tephra"),
        ExclusionRule("com.google.code.findbugs", "jsr305"),
        ExclusionRule("org.eclipse.jetty.aggregate", "module: 'jetty-all"),
        ExclusionRule("org.eclipse.jetty.orbit", "javax.servlet"),
        ExclusionRule("org.apache.parquet", "parquet-hadoop-bundle"),
        ExclusionRule("com.tdunning", "json"),
        ExclusionRule("javax.transaction", "transaction-api"),
        ExclusionRule("'com.zaxxer", "HikariCP"),
      ),
      // Exclusions due to conflicts with Flink's libraries from table planer, hive, calcite etc.
      "org.apache.hive" % "hive-exec" % "3.1.2" % "test" classifier "core" excludeAll(
        ExclusionRule("'org.apache.avro", "avro"),
        ExclusionRule("org.slf4j", "slf4j-log4j12"),
        ExclusionRule("org.pentaho"),
        ExclusionRule("com.google.code.findbugs", "jsr305"),
        ExclusionRule("org.apache.calcite.avatica"),
        ExclusionRule("org.apache.calcite"),
        ExclusionRule("org.apache.hive", "hive-llap-tez"),
        ExclusionRule("org.apache.logging.log4j"),
        ExclusionRule("com.google.protobuf", "protobuf-java"),
      ),
    ),
    // generating source java class with version number to be passed during commit to the DeltaLog as engine info
    // (part of transaction's metadata)
    Compile / sourceGenerators += Def.task {
      val file = (Compile / sourceManaged).value / "io" / "delta" / "flink" / "internal" / "Meta.java"
      IO.write(file,
        s"""package io.delta.flink.internal;
           |
           |final public class Meta {
           |  public static final String FLINK_VERSION = "${flinkVersion}";
           |  public static final String CONNECTOR_VERSION = "${version.value}";
           |}
           |""".stripMargin)
      Seq(file)
    },

    // Unidoc settings
    unidocSourceFilePatterns += SourceFilePattern("io/delta/flink/"),
    // TODO: this is the config that was used before archiving connectors but it has
    //  standalone-specific import orders
    javaCheckstyleSettings("connectors/dev/checkstyle.xml")
  ).configureUnidoc()

/**
 * Get list of python files and return the mapping between source files and target paths
 * in the generated package JAR.
 */
def listPythonFiles(pythonBase: File): Seq[(File, String)] = {
  val pythonExcludeDirs = pythonBase / "lib" :: pythonBase / "doc" :: pythonBase / "bin" :: Nil
  import scala.collection.JavaConverters._
  val pythonFiles = Files.walk(pythonBase.toPath).iterator().asScala
    .map { path => path.toFile() }
    .filter { file => file.getName.endsWith(".py") && ! file.getName.contains("test") }
    .filter { file => ! pythonExcludeDirs.exists { base => IO.relativize(base, file).nonEmpty} }
    .toSeq

  pythonFiles pair Path.relativeTo(pythonBase)
}

ThisBuild / parallelExecution := false

val createTargetClassesDir = taskKey[Unit]("create target classes dir")

/*
 ******************
 * Project groups *
 ******************
 */

// Don't use these groups for any other projects
lazy val sparkGroup = project
  .aggregate(spark, contribs, storage, storageS3DynamoDB, iceberg, testDeltaIcebergJar, sharing)
  .settings(
    // crossScalaVersions must be set to Nil on the aggregating project
    crossScalaVersions := Nil,
    publishArtifact := false,
    publish / skip := false,
  )

lazy val kernelGroup = project
  .aggregate(kernelApi, kernelDefaults)
  .settings(
    // crossScalaVersions must be set to Nil on the aggregating project
    crossScalaVersions := Nil,
    publishArtifact := false,
    publish / skip := false,
    unidocSourceFilePatterns := {
      (kernelApi / unidocSourceFilePatterns).value.scopeToProject(kernelApi) ++
      (kernelDefaults / unidocSourceFilePatterns).value.scopeToProject(kernelDefaults)
    }
  ).configureUnidoc(docTitle = "Delta Kernel")

/*
 ***********************
 * ScalaStyle settings *
 ***********************
 */
ThisBuild / scalastyleConfig := baseDirectory.value / "scalastyle-config.xml"

lazy val compileScalastyle = taskKey[Unit]("compileScalastyle")
lazy val testScalastyle = taskKey[Unit]("testScalastyle")

lazy val scalaStyleSettings = Seq(
  compileScalastyle := (Compile / scalastyle).toTask("").value,

  Compile / compile := ((Compile / compile) dependsOn compileScalastyle).value,

  testScalastyle := (Test / scalastyle).toTask("").value,

  Test / test := ((Test / test) dependsOn testScalastyle).value
)

/*
 ****************************
 * Java checkstyle settings *
 ****************************
 */

def javaCheckstyleSettings(checkstyleFile: String): Def.SettingsDefinition = {
  // Can be run explicitly via: build/sbt $module/checkstyle
  // Will automatically be run during compilation (e.g. build/sbt compile)
  // and during tests (e.g. build/sbt test)
  Seq(
    checkstyleConfigLocation := CheckstyleConfigLocation.File(checkstyleFile),
    checkstyleSeverityLevel := Some(CheckstyleSeverityLevel.Error),
    (Compile / checkstyle) := (Compile / checkstyle).triggeredBy(Compile / compile).value,
    (Test / checkstyle) := (Test / checkstyle).triggeredBy(Test / compile).value
  )
}

/*
 ********************
 * Release settings *
 ********************
 */
import ReleaseTransformations._

lazy val skipReleaseSettings = Seq(
  publishArtifact := false,
  publish / skip := true
)


// Release settings for artifact that contains only Java source code
lazy val javaOnlyReleaseSettings = releaseSettings ++ Seq(
  // drop off Scala suffix from artifact names
  crossPaths := false,

  // we publish jars for each scalaVersion in crossScalaVersions. however, we only need to publish
  // one java jar. thus, only do so when the current scala version == default scala version
  publishArtifact := {
    val (expMaj, expMin, _) = getMajorMinorPatch(default_scala_version.value)
    s"$expMaj.$expMin" == scalaBinaryVersion.value
  },

  // exclude scala-library from dependencies in generated pom.xml
  autoScalaLibrary := false,
)

lazy val releaseSettings = Seq(
  publishMavenStyle := true,
  publishArtifact := true,
  Test / publishArtifact := false,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  releaseCrossBuild := true,
  pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toArray),

  // TODO: This isn't working yet ...
  sonatypeProfileName := "io.delta", // sonatype account domain name prefix / group ID
  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    sys.env.getOrElse("SONATYPE_USERNAME", ""),
    sys.env.getOrElse("SONATYPE_PASSWORD", "")
  ),
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value) {
      Some("snapshots" at nexus + "content/repositories/snapshots")
    } else {
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    }
  },
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
  pomExtra :=
    <url>https://delta.io/</url>
      <scm>
        <url>git@github.com:delta-io/delta.git</url>
        <connection>scm:git:git@github.com:delta-io/delta.git</connection>
      </scm>
      <developers>
        <developer>
          <id>marmbrus</id>
          <name>Michael Armbrust</name>
          <url>https://github.com/marmbrus</url>
        </developer>
        <developer>
          <id>brkyvz</id>
          <name>Burak Yavuz</name>
          <url>https://github.com/brkyvz</url>
        </developer>
        <developer>
          <id>jose-torres</id>
          <name>Jose Torres</name>
          <url>https://github.com/jose-torres</url>
        </developer>
        <developer>
          <id>liwensun</id>
          <name>Liwen Sun</name>
          <url>https://github.com/liwensun</url>
        </developer>
        <developer>
          <id>mukulmurthy</id>
          <name>Mukul Murthy</name>
          <url>https://github.com/mukulmurthy</url>
        </developer>
        <developer>
          <id>tdas</id>
          <name>Tathagata Das</name>
          <url>https://github.com/tdas</url>
        </developer>
        <developer>
          <id>zsxwing</id>
          <name>Shixiong Zhu</name>
          <url>https://github.com/zsxwing</url>
        </developer>
        <developer>
          <id>scottsand-db</id>
          <name>Scott Sandre</name>
          <url>https://github.com/scottsand-db</url>
        </developer>
        <developer>
          <id>windpiger</id>
          <name>Jun Song</name>
          <url>https://github.com/windpiger</url>
        </developer>
      </developers>
)

// Looks like some of release settings should be set for the root project as well.
publishArtifact := false  // Don't release the root project
publish / skip := true
publishTo := Some("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots")
releaseCrossBuild := false  // Don't use sbt-release's cross facility
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("+publishSigned"),
  // Do NOT use `sonatypeBundleRelease` - it will actually release to Maven! We want to do that
  // manually.
  //
  // Do NOT use `sonatypePromote` - it will promote the closed staging repository (i.e. sync to
  //                                Maven central)
  //
  // See https://github.com/xerial/sbt-sonatype#publishing-your-artifact.
  //
  // - sonatypePrepare: Drop the existing staging repositories (if exist) and create a new staging
  //                    repository using sonatypeSessionName as a unique key
  // - sonatypeBundleUpload: Upload your local staging folder contents to a remote Sonatype
  //                         repository
  // - sonatypeClose: closes your staging repository at Sonatype. This step verifies Maven central
  //                  sync requirement, GPG-signature, javadoc and source code presence, pom.xml
  //                  settings, etc
  // TODO: this isn't working yet
  // releaseStepCommand("sonatypePrepare; sonatypeBundleUpload; sonatypeClose"),
  setNextVersion,
  commitNextVersion
)
