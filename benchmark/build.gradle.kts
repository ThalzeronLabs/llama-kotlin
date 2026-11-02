// A command-line benchmark that runs on the device: install the APK, push a model, run it with `adb shell am instrument`.
// See benchmark/README.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.thalzeronlabs.llama.benchmark"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thalzeronlabs.llama.benchmark"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        val libraryVersion = providers.gradleProperty("VERSION_NAME").orElse("unspecified").get()
        versionName = libraryVersion
        buildConfigField("String", "LIBRARY_VERSION", "\"$libraryVersion\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Measure the optimized build, signed with the local debug key so it installs without any setup.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Same opt-in host run as the library: -Pllama.nativeLibDir=... -Pllama.testModel=... (see CONTRIBUTING.md).
        unitTests.all { test ->
            providers.gradleProperty("llama.nativeLibDir").orNull?.let {
                test.systemProperty("java.library.path", rootProject.file(it).absolutePath)
            }
            providers.gradleProperty("llama.testModel").orNull?.let {
                test.systemProperty("llama.testModel", rootProject.file(it).absolutePath)
            }
        }
    }
}

dependencies {
    implementation(project(":llama"))

    testImplementation(libs.junit)
}
