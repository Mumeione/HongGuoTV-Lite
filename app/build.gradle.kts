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

// 分发合规：GPLv3 分发目标代码时要求随附许可证文本。根目录那一份是唯一来源
// （不在 src 下再放副本，两份必然漂移），构建期拷进 APK 的 assets。
val legalAssetsDir = layout.buildDirectory.dir("generated/legalAssets")
val copyLegalDocs = tasks.register<Copy>("copyLegalDocs") {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE.md"))
    into(legalAssetsDir)
}

android {
    namespace = "com.hongguotv.lite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hongguotv.lite"
        minSdk = 23
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "1.0.0"
    }

    buildFeatures { buildConfig = true }

    // 平台基线是 API 23：任何超版本 API 都必须在构建期拦住，而不是等用户家的老电视崩。
    // core 是纯 JVM 模块，Lint 看不到它，那边由 core 的 verifyJvmApiLevel 任务兜住。
    lint {
        abortOnError = true
        error += "NewApi"
    }

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

    sourceSets["main"].assets.srcDir(legalAssetsDir)
}

// 资源合并任务不保证经 preBuild，显式挂上，避免增量构建时 assets 里少两份文件。
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyLegalDocs) }
tasks.named("preBuild") { dependsOn(copyLegalDocs) }

dependencies {
    // 顶层是唯一知道"谁实现谁"的地方：所有端口实现在这里组装。
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":uikit"))
    implementation(project(":feature-catalog"))
    implementation(project(":feature-detail"))
    implementation(project(":feature-player"))
    implementation("androidx.core:core:1.8.0")
    // 组装层要亲手建 OkHttpClient（内容端点一个、封面一个，超时不同）并递给 data 的实现。
    // data 里它是 implementation，不对外暴露，所以这里必须自己声明一次。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 宿主建主线程作用域（Dispatchers.Main）。feature 的作用域由 app 注入，运行时由这里提供。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 路由栈是不碰 android.* 的纯逻辑，用普通 JVM 单测覆盖（与 uikit / feature-* 同口径）。
    testImplementation("junit:junit:4.13.2")
}
