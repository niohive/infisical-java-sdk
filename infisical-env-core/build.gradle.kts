plugins {
    `java-library`
    `java-test-fixtures`
}

description = "Loads secrets from Infisical into a Map. Java 8+, no runtime dependencies."

extra["javaRelease"] = "8"
extra["testJdks"] = "8,11,21,25"
extra["allowedPomDependencies"] = ""
apply(from = rootProject.file("gradle/java-module.gradle.kts"))

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.niohive.infisical.env")
    }
}
