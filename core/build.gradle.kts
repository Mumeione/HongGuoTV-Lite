plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

// 纯 JVM：没有 Android SDK，import android.* 会直接编译失败。
// 这是本项目最重要的一条边界——core 必须能脱离设备单测。
dependencies {
    // 协议表解析用 org.json。Android 运行时由系统提供，所以用 compileOnly 不下发，
    // 避免与平台的 org.json 重复打包。
    // 代价：任何**在纯 JVM 上**跑 core 的模块（data 的单测、S8 的 CLI 探针）必须自带
    // org.json，否则运行时 NoClassDefFoundError。core 自己的单测已用 testImplementation 供上。
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    // usecase 里的 suspend 流程要用 runBlocking 驱动（core 自身不依赖协程）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> { useJUnit() }

// ─────────────────────────────────────────────────────────────────────────────
// 平台基线守卫（API 23）
//
// Android 模块靠 Lint 的 NewApi，但 Lint 看不到纯 JVM 模块——core 里的越界调用
// 在编译期、Lint 期都不会报，只会在老设备上 NoClassDefFoundError。
//
// 高版本 JDK 类的名字在 class 文件常量池里是明文，直接扫字节即可，不需要额外依赖。
// 这条守卫的价值不在拦截现有代码，而在拦住**以后写的**代码：S2–S6 的业务层如果
// 用了 API 24+，将来降版本就要回挖。
// ─────────────────────────────────────────────────────────────────────────────
val androidApiCeiling = 23

val forbiddenJdkApis: List<Pair<String, Int>> = listOf(
    "java/util/Base64" to 26,
    "java/time/" to 26,
    "java/nio/file/" to 26,
    "java/util/stream/" to 24,
    "java/util/function/" to 24,
    "java/util/Optional" to 24,
    "java/util/concurrent/CompletableFuture" to 24,
)

fun ByteArray.indexOfAscii(needle: String): Int {
    val target = needle.toByteArray(Charsets.US_ASCII)
    if (target.isEmpty()) return 0
    outer@ for (i in 0..(size - target.size)) {
        for (j in target.indices) if (this[i + j] != target[j]) continue@outer
        return i
    }
    return -1
}

val verifyJvmApiLevel by tasks.registering {
    group = "verification"
    description = "扫描字节码，拦截高于 Android API $androidApiCeiling 的 JDK 类引用"
    dependsOn(tasks.named("classes"))
    // Kotlin 是当前唯一源码语言；java/main 一并扫上，防止将来混入 Java 源文件绕过守卫。
    val classesDirs = listOf(
        layout.buildDirectory.dir("classes/kotlin/main"),
        layout.buildDirectory.dir("classes/java/main"),
    )
    doLast {
        val hits = mutableListOf<String>()
        classesDirs.map { it.get().asFile }
            .filter { it.isDirectory }
            .forEach { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .forEach { file ->
                        val bytes = file.readBytes()
                        forbiddenJdkApis.forEach { (needle, level) ->
                            if (level > androidApiCeiling && bytes.indexOfAscii(needle) >= 0) {
                                hits += "${file.relativeTo(root).path} 引用了 $needle（API $level）"
                            }
                        }
                    }
            }
        if (hits.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("core 里出现了高于 API $androidApiCeiling 的 JDK 类：")
                    hits.sorted().forEach { appendLine("  ✕ $it") }
                    appendLine("替代方案见 docs/PLATFORM.md 的 API 红线表。")
                }
            )
        }
    }
}

tasks.named("test") { dependsOn(verifyJvmApiLevel) }
// Android 模块经 project 依赖消费 core 时触发的是 :core:jar（而非 test）——
// 挂上它，任何产物构建都会先过一遍守卫。
tasks.named("jar") { dependsOn(verifyJvmApiLevel) }

// ─────────────────────────────────────────────────────────────────────────────
// 产物守卫：kotlin_module 门面表
//
// 2026-10-21 实测：构建缓存（本机 gradle.properties 开了 org.gradle.caching=true）
// 存过一份坏的 core 产物——META-INF/core.kotlin_module 是 24 字节空表
// （正常是"包名 → XxxKt"门面清单，约 140 字节）。症状极有迷惑性：
// 下游类全部解析得到、**顶层函数全部 Unresolved**；clean 也没用，
// 因为坏条目会被缓存原样还原（:core:compileKotlin FROM-CACHE）。
//
// 这条守卫在 jar 阶段拦住它：门面表里没有任何 Kt 门面 = 编译产物坏了，直接报错，
// 不等下游一片红。修法：./gradlew :core:compileKotlin --rerun-tasks
// （不读缓存，重编出的好产物覆盖同名缓存条目）。
// ─────────────────────────────────────────────────────────────────────────────
val verifyKotlinModuleTable by tasks.registering {
    group = "verification"
    description = "校验 kotlin_module 门面表非空（顶层函数要能被下游解析）"
    dependsOn(tasks.named("compileKotlin"))
    val moduleFile = layout.buildDirectory.file("classes/kotlin/main/META-INF/core.kotlin_module")
    doLast {
        val file = moduleFile.get().asFile
        if (!file.isFile) {
            throw GradleException("core 产物缺 META-INF/core.kotlin_module，下游对顶层函数的引用会全部 Unresolved。")
        }
        val bytes = file.readBytes()
        // 门面表的字符串是明文存储；空表只有包名，不含任何 "Kt" 门面名。
        val hasFacade = bytes.indexOfAscii("Kt") >= 0
        // 24 字节的空表之外再留一层尺寸下限：正常表至少要有几十字节的门面清单。
        if (bytes.size < 64 || !hasFacade) {
            throw GradleException(
                buildString {
                    appendLine("core.kotlin_module 是坏产物（${bytes.size} 字节，没有顶层函数的门面清单）。")
                    appendLine("症状：下游『类都在、顶层函数 Unresolved』，clean 无效（坏条目在构建缓存里）。")
                    appendLine("修法：./gradlew :core:compileKotlin --rerun-tasks（不读缓存，覆盖坏条目）。")
                },
            )
        }
    }
}

tasks.named("jar") { dependsOn(verifyKotlinModuleTable) }
