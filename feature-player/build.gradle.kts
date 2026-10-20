plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.hongguotv.lite.feature.player"
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

dependencies {
    implementation(project(":core"))
    implementation(project(":uikit"))
    // 播放器只在 player 模块内可见，app 拿不到 ExoPlayer 类型
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    // 取流是 suspend 的端口方法：作用域由 app 注入（S7），协程运行时由本模块提供。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
