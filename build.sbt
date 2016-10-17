import java.lang.Runtime._

import SharedSettings._
import android.Keys._
import com.android.tools.lint.checks.ApiDetector
import sbt.Keys._
import sbt._
import sbtassembly.MappingSet

val MajorVersion = "81"
val androidSdkLevel = "android-23"

version in ThisBuild := {
  val jobName = sys.env.get("JOB_NAME")
  val buildNumber = sys.env.get("BUILD_NUMBER")
  val master = jobName.exists(_.endsWith("-master"))
  val buildNumberString = buildNumber.fold("-SNAPSHOT")("." + _)
  if (master) MajorVersion + ".3" + buildNumberString // hotfix release
  else MajorVersion + buildNumberString
}

crossPaths in ThisBuild := false
organization in ThisBuild := "com.wire"

scalaVersion in ThisBuild := "2.11.8"

javacOptions in ThisBuild ++= Seq("-source", "1.7", "-target", "1.7", "-encoding", "UTF-8")
scalacOptions in ThisBuild ++= Seq("-feature", "-target:jvm-1.7", "-Xfuture", "-deprecation", "-Yinline-warnings", "-Ywarn-unused-import", "-encoding", "UTF-8")

platformTarget in ThisBuild := androidSdkLevel

licenses in ThisBuild += ("GPL-3.0", url("https://opensource.org/licenses/GPL-3.0"))

resolvers in ThisBuild ++= Seq(
  Resolver.mavenLocal,
  Resolver.jcenterRepo,
  Resolver.bintrayRepo("wire-android", "releases"),
  Resolver.bintrayRepo("wire-android", "snapshots"),
  Resolver.bintrayRepo("wire-android", "third-party"),
  "Maven central 1" at "http://repo1.maven.org/maven2",
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
  "Localytics" at "http://maven.localytics.com/public"
)

lazy val licenseHeaders = HeaderPlugin.autoImport.headers := Set("scala", "java", "rs") .map { _ -> GPLv3("2016", "Wire Swiss GmbH") } (collection.breakOut)

lazy val root = Project("zmessaging-android", file("."))
  .aggregate(macrosupport, utils, zmessaging, actors, testutils, unit, mocked, integration, actors, actors_android, actors_app, testapp)
  .settings(
    aggregate := false,
    aggregate in clean := true,
    aggregate in (Compile, compile) := true,

    test := {
      (ndkBuild in zmessaging).value
      (test in unit in Test).value
    },
    addCommandAlias("testQuick", ";unit/testQuick"),
    addCommandAlias("testOnly", ";unit/testOnly"),

    test in IntegrationTest := {
      (ndkBuild in zmessaging).value
      (test in integration in Test).value
    },
    testQuick in IntegrationTest := { println("use integration/testQuick") },
    testOnly in IntegrationTest := { println("use integration/testOnly") },

    publish := {
      (publish in zmessaging).value
      (publish in actors).value
      (publish in testutils).value
    },
    publishLocal := { (publishLocal in zmessaging).value },
    publishM2 := {
      (publishM2 in zmessaging).value
      (publishM2 in actors).value
      (publishM2 in testutils).value
    }
  )

lazy val zmessaging = project
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .dependsOn(macrosupport)
  .dependsOn(utils)
  .settings(android.Plugin.androidBuildAar: _*)
  .settings(publishSettings: _*)
  .settings(
    name := "zmessaging-android",
    crossPaths := false,
    platformTarget := androidSdkLevel,
    lintDetectors := Seq(ApiDetector.UNSUPPORTED),
    lintStrict := true,
    libraryProject := true,
    typedResources := false,
    sourceGenerators in Compile += generateZmsVersion.taskValue,
    ndkAbiFilter := Seq("armeabi-v7a", "x86"),
    ndkBuild := {
      val jni = ndkBuild.value
      val jniSrc = sourceDirectory.value / "main" / "jni"
      val osx = jni.head / "osx"
      osx.mkdirs()

      s"sh ${jniSrc.getAbsolutePath}/build_osx.sh".!

      jni
    },
    libraryDependencies ++= Seq(
      Deps.supportV4 % Provided,
      "com.koushikdutta.async" % "androidasync" % "2.1.8",
      "com.googlecode.libphonenumber" % "libphonenumber" % "7.1.1", // 7.2.x breaks protobuf
      "com.softwaremill.macwire" %% "macros" % "2.2.2" % Provided,
      "com.google.android.gms" % "play-services-base" % "7.8.0" % Provided exclude("com.android.support", "support-v4"),
      "com.google.android.gms" % "play-services-gcm" % "7.8.0" % Provided,
      Deps.avs % Provided,
      Deps.cryptobox,
      Deps.genericMessage,
      Deps.backendApi,
      "com.wire" % "icu4j-shrunk" % "57.1",
      Deps.spotifyPlayer,
      "org.threeten" % "threetenbp" % "1.3" % Provided,
      "com.googlecode.mp4parser" % "isoparser" % "1.1.7",
      "net.hockeyapp.android" % "HockeySDK" % "3.6.2",
      Deps.localytics,
      "org.robolectric" % "android-all" % RobolectricVersion % Provided
    )
  )

lazy val unit = project.in(file("tests") / "unit")
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .androidBuildWith(zmessaging)
  .dependsOn(testutils % Test)
  .settings(testSettings: _*)
  .settings(
    parallelExecution in Test := true,
    concurrentRestrictions in Global ++= Seq(Tags.limit(Tags.ForkedTestGroup, getRuntime.availableProcessors))
  )

lazy val integration = project.in(file("tests") / "integration")
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .androidBuildWith(zmessaging)
  .dependsOn(testutils % Test)
  .configs(RegressionTest)
  .settings(testSettings: _*)
  .settings(integrationCredentials: _*)
  .settings(
    parallelExecution in Test := false,
    libraryDependencies += "javax.mail" % "mail" % "1.4.7",
    testOptions in RegressionTest += Tests.Argument(TestFrameworks.ScalaTest, "-l", "com.waz.tags.FixMe")
  )

lazy val mocked = project.in(file("tests") / "mocked")
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .androidBuildWith(zmessaging)
  .dependsOn(testutils % Test)
  .settings(testSettings: _*)
  .settings(integrationCredentials: _*)
  .settings(
    parallelExecution in Test := false
  )

lazy val actors: Project = project.in(file("actors") / "base")
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .dependsOn(zmessaging)
  .settings(publishSettings: _*)
  .settings(
    name := "actors-core",
    exportJars := true,
    libraryDependencies ++= Seq(
      "org.robolectric" % "android-all" % RobolectricVersion % Provided,
      "org.threeten" % "threetenbp" % "1.3",
      "com.typesafe.akka" %% "akka-actor" % "2.3.14",
      "com.typesafe.akka" %% "akka-remote" % "2.3.14"
    )
  )

lazy val testutils = project.in(file("tests") / "utils")
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .dependsOn(actors)
  .settings(publishSettings: _*)
  .settings(
    name := "testutils",
    crossPaths := false,
    exportJars := false,
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "2.2.6",
      "com.wire" %% "robotest" % "0.7" exclude("org.scalatest", "scalatest"),
      "com.drewnoakes" % "metadata-extractor" % "2.8.1",
      "org.robolectric" % "android-all" % RobolectricVersion,
      "org.java-websocket" % "Java-WebSocket" % "1.3.0",
      "com.googlecode.mp4parser" % "isoparser" % "1.1.7"
    ),
    dependencyOverrides += "junit" % "junit" % "4.12"
  )

lazy val testapp = project.in(file("tests") / "app")
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .androidBuildWith(zmessaging)
  .settings(
    name := "testapp",
    crossPaths := false,
    libraryProject := false,
    platformTarget := androidSdkLevel,
    proguardConfig ++= IO.readLines(file("tests") / "app" / "proguard.txt"),
    proguardCache := Seq(),
    typedResources := false,
    retrolambdaEnabled := false,
    dexMulti := true,
    publishArtifact in (Compile, packageBin) := false,
    publishArtifact in (Compile, packageDoc) := false,
    useProguard := false,
    useProguardInDebug := useProguard.value,
    proguardScala := useProguard.value,
    debugIncludesTests := false,
    instrumentTestRunner := "android.support.test.runner.AndroidJUnitRunner",
    ndkAbiFilter := Seq("armeabi-v7a", "x86"),
    packagingOptions := {
      val p = packagingOptions.value
      p.copy(excludes = p.excludes ++ Seq("META-INF/DEPENDENCIES", "META-INF/DEPENDENCIES.txt", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/LICENSE", "LICENSE.txt", "META-INF/LICENSE.txt", "reference.conf"))
    },
    dexMainClasses := Seq("com.waz.testapp.EmptyTestActivity. com.waz.testapp.TestAssetProvider"),
    dexMaxHeap := "2048M",
    libraryDependencies ++= Seq (
      "com.android.support" % "multidex" % "1.0.1",
      "com.android.support" % "support-v4" % supportLibVersion,
      "com.jakewharton.threetenabp" % "threetenabp" % "1.0.3",
      Deps.avs,
      Deps.spotifyPlayer,
      Deps.spotifyAuth,
      "com.google.android.gms" % "play-services-base" % "7.8.0" exclude("com.android.support", "support-v4"),
      "com.google.android.gms" % "play-services-gcm" % "7.8.0",
      Deps.localytics,
      "junit" % "junit" % "4.12" % Test,
      "com.android.support" % "support-annotations" % "23.0.1" % Test,
      "com.android.support.test" % "runner" % "0.5" % Test,
      "com.android.support.test" % "rules" % "0.5" % Test
    )
  )

lazy val actors_android = project.in(file("actors") / "android_app")
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .dependsOn(actors)
  .androidBuildWith(zmessaging)
  .settings(
    name := "androidactors",
    crossPaths := false,
    libraryProject := false,
    platformTarget := androidSdkLevel,
    proguardOptions ++= IO.readLines(file("actors") / "android_app" / "proguard.txt"),
    proguardCache := Seq(),
    useProguard := true,
    useProguardInDebug := true,
    typedResources := false,
    retrolambdaEnabled := false,
    dexMulti := true,
    publishArtifact in (Compile, packageBin) := false,
    publishArtifact in (Compile, packageDoc) := false,
    packagingOptions := {
      val p = packagingOptions.value
      p.copy(excludes = p.excludes ++ Seq("META-INF/DEPENDENCIES", "META-INF/DEPENDENCIES.txt", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/LICENSE", "LICENSE.txt", "META-INF/LICENSE.txt", "reference.conf"))
    },
    dexMainClasses ++= Seq("com.waz.androidactors.MainActivity"),
    dexMaxHeap := "2048M",
    libraryDependencies ++= Seq (
      "com.android.support" % "multidex" % "1.0.1",
      "com.android.support" % "support-v4" % supportLibVersion,
      "com.android.support" % "recyclerview-v7" % supportLibVersion,
      "com.jakewharton.threetenabp" % "threetenabp" % "1.0.3",
      "net.hockeyapp.android" % "HockeySDK" % "3.5.0",
      Deps.avs,
      Deps.avsAudio,
      Deps.cryptobox,
      "com.google.android.gms" % "play-services-base" % "7.8.0" exclude("com.android.support", "support-v4"),
      "com.google.android.gms" % "play-services-gcm" % "7.8.0",
      Deps.localytics
    )
  )

lazy val actors_app: Project = project.in(file("actors") / "remote_app")
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .androidBuildWith(zmessaging)
  .configs(IntegrationTest)
  .dependsOn(testutils)
  .dependsOn(integration % "it -> test")
  .settings(Defaults.itSettings: _*)
  .settings(nativeLibsSettings: _*)
  .settings(publishSettings: _*)
  .settings (
    name := "zmessaging-actor",
    crossPaths := false,
    fork := true,
    typedResources := false,
    parallelExecution in IntegrationTest := true,
    javaOptions in IntegrationTest ++= Seq(libraryPathOption(nativeLibs.value)),
    test in IntegrationTest <<= (test in IntegrationTest).dependsOn(assembly),
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", "LICENSE") => MergeStrategy.discard
      case "application.conf"            => MergeStrategy.concat
      case "reference.conf"              => MergeStrategy.concat
      case x if x.startsWith("com/waz/znet/ClientWrapper") => MergeStrategy.last
      case _ => MergeStrategy.first

    },
    scalacOptions ++= Seq("-feature", "-target:jvm-1.7", "-Xfuture", "-deprecation", "-Yinline-warnings", "-Ywarn-unused-import", "-encoding", "UTF-8"),
    actorsResources := {
      val out = target.value / "actors_res.zip"
      val manifest = baseDirectory.value / "src" / "main" / "AndroidManifest.xml"
      val res = zmessaging.base / "src" / "main" / "res"
      val mappings = {
        val libs = nativeLibs.value.files.flatMap(d => (d ** "*").get).filter(_.isFile)
        val distinct = { // remove duplicate libs, always select first one, as nativeLibs are first on the path
          val names = new scala.collection.mutable.HashSet[String]()
          libs.filter { f => names.add(f.getName) }
        }
        println(s"libs: $libs\n distinct: $distinct")
        distinct.map(f => (f, s"/libs/${f.getName}")) ++ ((res ** "*").get pair rebase(res, "/res")) :+ (manifest, "/AndroidManifest.xml")
      }
      IO.zip(mappings, out)
      out
    },
    assemblyJarName := s"remote-actor-${version.value}.jar",
    assembledMappings in assembly := {
      val res = actorsResources.value
      assert(res.exists() && res.length() > 0)
      (assembledMappings in assembly).value :+ MappingSet(None, Vector((actorsResources.value, s"actor_res.jar")))
    },
    addArtifact(artifact in Compile, assembly),
    publishArtifact in (Compile, packageBin) := false,
    publishArtifact in (Compile, packageDoc) := false,
    pomPostProcess := { (node: scala.xml.Node) =>
      new scala.xml.transform.RuleTransformer(new scala.xml.transform.RewriteRule {
        override def transform(n: scala.xml.Node): scala.xml.NodeSeq =
          n.nameToString(new StringBuilder).toString match {
            case "dependency" | "repository" => scala.xml.NodeSeq.Empty
            case _ => n
          }
      }).transform(node).head
    },
    unmanagedResourceDirectories in Compile ++= Seq(root.base / "src/it/resources"),
    mainClass in assembly := Some("com.waz.RemoteActorApp"),
    libraryDependencies ++= Seq(
      "org.apache.httpcomponents" % "httpclient" % "4.5.1", // to override version included in robolectric
      "junit" % "junit" % "4.8.2", //to override version included in robolectric
      "org.apache.httpcomponents" % "httpcore" % "4.4.4",
      "org.robolectric" % "android-all" % RobolectricVersion,
      Deps.avs,
      "org.threeten" % "threetenbp" % "1.3",
      "com.wire" % "cryptobox-jni-osx" % cryptoboxVersion,
      Deps.localytics,
      "com.android.support" % "support-v4" % supportLibVersion % Provided,
      "com.google.android.gms" % "play-services-base" % "7.8.0" % Provided exclude("com.android.support", "support-v4"),
      "com.google.android.gms" % "play-services-gcm" % "7.8.0" % Provided
    )
  )

lazy val macrosupport = project
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .settings(publishSettings: _*)
  .settings(
    version := "3.0",
    crossPaths := false,
    exportJars := true,
    name := "zmessaging-android-macrosupport",
    bintrayRepository := "releases",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % (scalaVersion in ThisBuild).value % Provided,
      "org.robolectric" % "android-all" % RobolectricVersion % Provided
    )
  )

lazy val utils = project
  .enablePlugins(AutomateHeaderPlugin).settings(licenseHeaders)
  .settings(publishSettings: _*)
  .settings(android.Plugin.androidBuildAar: _*)
  .dependsOn(macrosupport)
  .settings(
    version := "1.0",
    name := "zmessaging-android-utils",
    bintrayRepository := "releases",
    crossPaths := false,
    platformTarget := androidSdkLevel,
    lintDetectors := Seq(ApiDetector.UNSUPPORTED),
    lintStrict := true,
    libraryProject := true,
    typedResources := false,
    libraryDependencies ++= Seq(
      "org.threeten" % "threetenbp" % "1.3" % Provided
    )
  )

generateZmsVersion in zmessaging := {
  val file = (sourceManaged in Compile in zmessaging).value / "com"/ "waz" / "api" / "ZmsVersion.java"
  val content = """package com.waz.api;
                  |
                  |public class ZmsVersion {
                  |   public static final String ZMS_VERSION = "%s";
                  |   public static final String AVS_VERSION = "%s";
                  |   public static final int ZMS_MAJOR_VERSION = %s;
                  |   public static final boolean DEBUG = %b;
                  |}
                """.stripMargin.format(version.value, avsVersion, MajorVersion, sys.env.get("BUILD_NUMBER").isEmpty || sys.props.getOrElse("debug", "false").toBoolean)
  IO.write(file, content)
  Seq(file)
}

lazy val fullCoverage = taskKey[Unit]("Runs all tests and generates coverage report of zmessaging")

fullCoverage := {
  (test in unit in Test).value
  (test in mocked in Test).value
  (test in integration in Test).value
  (coverageReport in zmessaging).value
}
