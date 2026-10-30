plugins {
    checkstyle
    `java-library`
    // JsonParser, which the cli tests read --json output with; the product never parses JSON.
    `java-test-fixtures`
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
    // The core has no runtime dependencies: it reads recordings through jdk.jfr.consumer.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // The JFR-backed tests start in-process recordings; give them a predictable heap.
    maxHeapSize = "1g"
    // NativeThreads attaches a thread from native code through an FFM upcall; without this the JVM
    // prints a warning for the restricted method the first time.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // HashTablesTest replays a failing run from its seed: ./gradlew :core:test -Djfrq.test.seed=<seed>
    providers.systemProperty("jfrq.test.seed").orNull?.let { systemProperty("jfrq.test.seed", it) }
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
                minimum = "0.85".toBigDecimal()
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
