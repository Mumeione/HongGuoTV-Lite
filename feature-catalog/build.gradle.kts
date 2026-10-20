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
    // 页面要调 suspend 的端口方法：作用域由 app 注入（S7），协程运行时由本模块提供。
    // data 已经打包了 coroutines-core，这里不会重复计入体积；-android 只为 Main 调度器。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
