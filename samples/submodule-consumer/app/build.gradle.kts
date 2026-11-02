plugins { alias(libs.plugins.android.application) }
android {
    namespace = "demo.consumer"
    compileSdk = 37
    defaultConfig { applicationId = "demo.consumer"; minSdk = 26; targetSdk = 37; ndk { abiFilters += "arm64-v8a" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(project(":llama")) }
