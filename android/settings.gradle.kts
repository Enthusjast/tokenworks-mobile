pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "tokenworks-mobile"
include(":core:protocol", ":core:crypto", ":core:auth", ":core:session", ":core:network")
if (providers.gradleProperty("jvmOnly").orNull != "true") include(":core:keystore", ":core:ui", ":app")
