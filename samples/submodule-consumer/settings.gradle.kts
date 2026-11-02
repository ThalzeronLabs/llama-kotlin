// An app that includes llama-kotlin's :llama module from a git submodule checkout, the way an app repository would
// (for example with `git submodule add https://github.com/ThalzeronLabs/llama-kotlin`). Its own version catalog has
// no Dokka entry and it sets no VERSION_NAME. CI compiles it so that kind of setup keeps working.
//
//   ./gradlew -p samples/submodule-consumer :app:assembleDebug     (from the llama-kotlin checkout)
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "submodule-consumer"
include(":app")
include(":llama")
// In a real app this is the submodule's folder, e.g. file("llama-kotlin/llama").
project(":llama").projectDir = file(providers.gradleProperty("llamaKotlinDir").orElse("../..").get() + "/llama")
