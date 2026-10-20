plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.hongguotv.lite.uikit"
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
    buildFeatures { buildConfig = false }
}

dependencies {
    // UiKit 的公开 API 里出现 ArtworkPort（封面端口）：对消费者必须可见，所以是 api 而不是 implementation。
    api(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
