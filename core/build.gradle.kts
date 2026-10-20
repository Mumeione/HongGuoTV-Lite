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
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> { useJUnit() }
