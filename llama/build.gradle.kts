plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    `maven-publish`
}

group = "com.github.ThalzeronLabs"
version = providers.gradleProperty("VERSION_NAME").get()

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

dokka {
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
