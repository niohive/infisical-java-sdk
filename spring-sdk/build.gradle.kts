plugins {
    `java-library`
}

description = "Spring Boot 4 EnvironmentPostProcessor that adds Infisical secrets to the Environment."

extra["javaRelease"] = "17"
extra["testJdks"] = "25"
extra["allowedPomDependencies"] = "com.niohive.infisical:core"
apply(from = rootProject.file("gradle/java-module.gradle.kts"))

dependencies {
    api(project(":core"))
    // The application brings its own Spring Boot; never published.
    compileOnly(libs.spring.boot)

    testImplementation(libs.spring.boot)
    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.niohive.infisical.spring")
    }
}
