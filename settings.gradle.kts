pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CardDock"
include(":app", ":core")
includeBuild("experiments/mika-android") {
    dependencySubstitution {
        substitute(module("com.wangzan101.mika:embedded")).using(project(":embedded"))
    }
}
