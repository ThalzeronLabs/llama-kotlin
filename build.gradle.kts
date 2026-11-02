import org.jetbrains.dokka.gradle.DokkaExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.dokka) apply false
}

// The API reference is set up here, not in llama/build.gradle.kts, so that a project including :llama as a git
// submodule does not need the Dokka plugin (this file only runs when llama-kotlin is the root build).
// Dokka is applied right after the Android library plugin: applied before it, Dokka finds no Android sources.
project(":llama") {
    pluginManager.withPlugin("com.android.library") {
        apply(plugin = "org.jetbrains.dokka")
        extensions.configure<DokkaExtension> {
            moduleName.set("llama-kotlin")
            dokkaSourceSets.configureEach {
                includes.from("Module.md")
                sourceLink {
                    localDirectory.set(file("src/main/kotlin"))
                    remoteUrl("https://github.com/ThalzeronLabs/llama-kotlin/blob/main/llama/src/main/kotlin")
                    remoteLineSuffix.set("#L")
                }
            }
        }
    }
}
