plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

// 纯 JVM：没有 Android SDK，import android.* 会直接编译失败。
// 这是本项目最重要的一条边界——core 必须能脱离设备单测。
dependencies {
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> { useJUnit() }
