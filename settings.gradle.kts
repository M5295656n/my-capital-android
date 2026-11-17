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
rootProject.name = "MyCapital"
include(":app", ":ai-benchmark", ":llama-android")
project(":llama-android").projectDir = file("ai-benchmark/llama.cpp/examples/llama.android/lib")
