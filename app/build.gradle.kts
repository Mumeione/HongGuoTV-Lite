import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val signingProperties = Properties().apply {
    val config = rootProject.file("keystore.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hongguotv.lite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hongguotv.lite"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "1.0.0-lite"
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("delivery")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}

dependencies {
    // 顶层是唯一知道"谁实现谁"的地方：所有端口实现在这里组装。
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":uikit"))
    implementation(project(":feature-catalog"))
    implementation(project(":feature-detail"))
    implementation(project(":feature-player"))
    implementation("androidx.core:core:1.8.0")
}
