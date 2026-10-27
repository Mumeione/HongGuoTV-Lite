plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.hongguotv.lite.data"
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
    // ContentPort 是 suspend 接口：IO 调度与官网节流的 delay 都靠它。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Android 运行时已提供 org.json（android.jar），不要再打包一份；
    // 只有本模块的 JVM 单测需要真实实现。
    testImplementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(kotlin("test"))
}
