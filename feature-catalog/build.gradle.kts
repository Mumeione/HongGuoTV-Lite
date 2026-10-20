plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.hongguotv.lite.feature.catalog"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 只依赖 core 的接口与 uikit 的控件；不依赖 data，也不依赖其他 feature。
dependencies {
    implementation(project(":core"))
    implementation(project(":uikit"))
    testImplementation("junit:junit:4.13.2")
}
