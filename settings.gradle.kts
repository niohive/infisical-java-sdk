rootProject.name = "infisical-java-sdk"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
}

include("core", "spring-sdk")
