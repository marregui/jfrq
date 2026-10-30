plugins {
    checkstyle
    application
    jacoco
}

group = "dev.jfrq"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // The JVM side is the JDK's own: jdk.attach to reach the process, jdk.management.jfr to
    // drive its recorder over JMX. Both are modules of every full JDK, so nothing is added.
    implementation(project(":core"))
    implementation(project(":cli"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "jfrq-live"
    mainClass = "dev.jfrq.live.Live"
}

// jfrq's launcher, on cli and core only (live's runtime classpath, without live's own jar).
// Start-up is most of the cost on a small recording. AppCDS keeps a dynamic archive of the
// loaded classes next to the jars after the first run; later runs map it instead of loading
// and verifying jdk.jfr and the tool again. Logging is off so an unwritable install directory
// degrades silently to a normal start.
val jfrqStartScripts = tasks.register<CreateStartScripts>("jfrqStartScripts") {
    applicationName = "jfrq"
    mainClass = "dev.jfrq.cli.Main"
    classpath = files(configurations.runtimeClasspath)
    outputDir = layout.buildDirectory.dir("jfrq-scripts").get().asFile
    defaultJvmOpts = listOf(
        "-XX:+AutoCreateSharedArchive",
        "-XX:SharedArchiveFile=APP_HOME_PLACEHOLDER/lib/jfrq.jsa",
        "-Xlog:cds*=off",
    )
    doLast {
        // The start scripts quote each JVM argument; APP_HOME is only known at run time.
        unixScript.writeText(unixScript.readText().replace("APP_HOME_PLACEHOLDER", "'\"\$APP_HOME\"'"))
        windowsScript.writeText(windowsScript.readText().replace("APP_HOME_PLACEHOLDER", "%APP_HOME%"))
    }
}

// The one distribution of the tools: jfrq-live already carries cli and core, so jfrq's
// launcher is all a jfrq distribution of its own would add. installDist puts both launchers
// in live/build/install/jfrq/bin; distZip writes jfrq-<version>.zip.
distributions {
    main {
        distributionBaseName = "jfrq"
        contents {
            from(jfrqStartScripts) {
                into("bin")
                filePermissions { unix("rwxr-xr-x") }
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // The tests attach to the JVM they run in; the JDK refuses that unless asked.
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.javadoc)
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Javadoc is checked, not published: doclint over every member, private ones included, so a broken
// {@link}, reference or tag fails the build. A member without a comment is not an error (G-10.4).
tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        memberLevel = JavadocMemberLevel.PRIVATE
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
    }
}

// What -Xlint cannot check: the Java Coding Standard v2.0.0 Checkstyle configuration, over every source set.
checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    configProperties = mapOf("projectPackagePattern" to "^dev\\.jfrq\\.")
    maxWarnings = 0
}
