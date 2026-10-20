plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

// 纯 JVM：没有 Android SDK，import android.* 会直接编译失败。
// 这是本项目最重要的一条边界——core 必须能脱离设备单测。
dependencies {
    // 主代码目前不解析 JSON（端点/字段表在 S1b 引入时再改为 implementation）；
    // 测试读向量文件需要 org.json。
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> { useJUnit() }
