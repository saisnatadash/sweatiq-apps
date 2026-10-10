plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
base { archivesName.set("SweatIQ-Control") }
android {
    namespace = "com.grassion.sweatiq.control"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.grassion.sweatiq.control"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":common"))
}
