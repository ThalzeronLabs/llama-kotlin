// This module also builds when another project includes it as a git submodule. So it needs nothing from the
// including build beyond the Android library plugin and the libraries below: the version falls back to
// "unspecified", and Dokka is configured by this repository's root build.gradle.kts, not here.
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.github.ThalzeronLabs"
version = providers.gradleProperty("VERSION_NAME").orElse("unspecified").get()

android {
    namespace = "com.thalzeronlabs.llama"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Host integration tests (NativeIntegrationTest, the GBNF validator check) run only when these are given,
        // e.g. -Pllama.nativeLibDir=build-host -Pllama.testModel=... (see CONTRIBUTING.md).
        unitTests.all { test ->
            providers.gradleProperty("llama.nativeLibDir").orNull?.let {
                test.systemProperty("java.library.path", rootProject.file(it).absolutePath)
            }
            listOf("llama.testModel", "llama.testEmbeddingModel", "llama.gbnfValidator").forEach { key ->
                providers.gradleProperty(key).orNull?.let { test.systemProperty(key, rootProject.file(it).absolutePath) }
            }
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Flow is part of the public API, so consumers get coroutines on their compile classpath too.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// AGP creates the "release" software component late, hence afterEvaluate.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.github.ThalzeronLabs"
                artifactId = "llama-kotlin"
                version = project.version.toString()

                from(components["release"])

                pom {
                    name.set("llama-kotlin")
                    description.set("Coroutine-first Kotlin API over llama.cpp: run GGUF language models fully on-device on Android.")
                    url.set("https://github.com/ThalzeronLabs/llama-kotlin")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("ThalzeronLabs")
                            name.set("Esdras Souza")
                            url.set("https://github.com/ThalzeronLabs")
                        }
                    }
                    scm {
                        url.set("https://github.com/ThalzeronLabs/llama-kotlin")
                        connection.set("scm:git:https://github.com/ThalzeronLabs/llama-kotlin.git")
                    }
                }
            }
        }
    }
}

// The licenses of everything bundled in this build travel INSIDE it, so a copy passed around on its own still carries
// them. In the AAR the file has its own path, assets/llama-kotlin/THIRD-PARTY-NOTICES.txt: Android merges the assets of
// an app and its libraries, and on a name clash the app's file wins, which would silently drop the llama.cpp notice.
abstract class CopyThirdPartyNotices : DefaultTask() {
    @get:InputFile
    abstract val notices: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        notices.get().asFile.copyTo(outputDir.get().file("llama-kotlin/THIRD-PARTY-NOTICES.txt").asFile, overwrite = true)
    }
}

val copyThirdPartyNotices = tasks.register<CopyThirdPartyNotices>("copyThirdPartyNotices") {
    notices.set(layout.projectDirectory.file("../THIRD-PARTY-NOTICES.txt"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyThirdPartyNotices, CopyThirdPartyNotices::outputDir)
    }
}
