// Shared conventions for both modules. Each module sets these extra properties before applying:
//   javaRelease            bytecode level, e.g. "8"
//   testJdks               extra JDKs to run the tests on, e.g. "8,11,21,25" (the default test task uses 17)
//   allowedPomDependencies comma-separated groupId:artifactId list the published POM may contain

apply(plugin = "maven-publish")

val javaRelease = (project.extra["javaRelease"] as String).toInt()
val testJdks = (project.extra["testJdks"] as String).split(',').map(String::trim).filter(String::isNotEmpty).map(String::toInt)
val allowedPomDependencies = (project.extra["allowedPomDependencies"] as String)
    .split(',').map(String::trim).filter(String::isNotEmpty).toSortedSet()

configure<JavaPluginExtension> {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaRelease)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
}

tasks.named<Javadoc>("javadoc") {
    exclude("**/internal/**")
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// MIT asks for the notice in every copy, so every jar (classes, sources, javadoc) carries it.
val licenseFile = rootProject.file("LICENSE")
tasks.withType<Jar>().configureEach {
    from(licenseFile) { into("META-INF") }
}

// JDK test matrix: the default test task runs on the 17 toolchain, testJavaN on JDK N.
val sourceSets = the<SourceSetContainer>()
val toolchains = the<JavaToolchainService>()
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
tasks.named<Test>("test") { systemProperty("expected.java", "17") }
testJdks.forEach { v ->
    tasks.register<Test>("testJava$v") {
        description = "Runs the tests on JDK $v."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(v)) })
        systemProperty("expected.java", if (v == 8) "1.8" else "$v")
    }
}
tasks.register("testAllJdks") {
    description = "Runs the tests on every supported JDK."
    group = "verification"
    dependsOn(tasks.withType<Test>())
}

// Publishing to GitHub Packages. Credentials come only from the GitHubPackagesUsername and
// GitHubPackagesPassword properties (set by the release workflow from GITHUB_TOKEN).
val repositoryUrl = "https://github.com/niohive/infisical-java-sdk"
configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("${rootProject.name} ${project.name}")
                description.set(provider { project.description })
                url.set(repositoryUrl)
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                organization {
                    name.set("NioHive")
                    url.set("https://github.com/niohive")
                }
                developers {
                    developer {
                        id.set("niohive")
                        name.set("NioHive")
                        organization.set("NioHive")
                        organizationUrl.set("https://github.com/niohive")
                    }
                }
                scm {
                    url.set(repositoryUrl)
                    connection.set("scm:git:$repositoryUrl.git")
                    developerConnection.set("scm:git:$repositoryUrl.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/niohive/infisical-java-sdk")
            credentials(PasswordCredentials::class)
        }
    }
}

// Test fixtures are for this build's tests only and must never be published.
plugins.withId("java-test-fixtures") {
    val java = components["java"] as AdhocComponentWithVariants
    java.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    java.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

val pomFile = layout.buildDirectory.file("publications/maven/pom-default.xml")
val moduleFile = layout.buildDirectory.file("publications/maven/module.json")
val verifyPublishedDependencies = tasks.register("verifyPublishedDependencies") {
    description = "Fails if the published POM declares a dependency outside the allowed set, or test fixtures are published."
    group = "verification"
    dependsOn("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")
    inputs.file(pomFile)
    inputs.file(moduleFile)
    val allowed = allowedPomDependencies
    val pom = pomFile
    val module = moduleFile
    doLast {
        check(!module.get().asFile.readText().contains("testFixtures")) {
            "Test fixtures would be published; they must be skipped"
        }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(pom.get().asFile)
        val nodes = doc.getElementsByTagName("dependency")
        val found = (0 until nodes.length).map { i ->
            val e = nodes.item(i) as org.w3c.dom.Element
            e.getElementsByTagName("groupId").item(0).textContent.trim() + ":" +
                e.getElementsByTagName("artifactId").item(0).textContent.trim()
        }.toSortedSet()
        check(found == allowed) { "Published POM dependencies $found do not match the allowed set $allowed" }
    }
}
tasks.named("check") { dependsOn(verifyPublishedDependencies) }

// Dependency locking. Regenerate locks and verification metadata from an empty Gradle user home;
// see CONTRIBUTING.md.
dependencyLocking { lockAllConfigurations() }
tasks.register("resolveAndLockAll") {
    description = "Resolves every resolvable configuration so --write-locks records it."
    notCompatibleWithConfigurationCache("Filters configurations at execution time")
    doFirst { require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" } }
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}
