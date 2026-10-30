plugins {
    checkstyle
    application
}

group = "dev.jfrq"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.netty.transport)
    implementation(libs.netty.codec.base)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "netty-demo"
    mainClass = "dev.jfrq.demo.DemoApp"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
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

tasks.check {
    dependsOn(tasks.javadoc)
}
