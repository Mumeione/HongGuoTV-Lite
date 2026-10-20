plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.hongguotv.lite.feature.catalog"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // 平台基线是 API 23：越界 API 一律构建期报错（与 app 模块同一道 NewApi 守卫）。
    lint {
        abortOnError = true
        error += "NewApi"
    }
}

// 只依赖 core 的接口与 uikit 的控件；不依赖 data，也不依赖其他 feature。
dependencies {
    implementation(project(":core"))
    implementation(project(":uikit"))
    testImplementation("junit:junit:4.13.2")
}
