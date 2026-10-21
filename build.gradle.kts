plugins {
    id("com.android.application") version "8.11.0" apply false
    id("com.android.library") version "8.11.0" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("jvm") version "2.1.20" apply false
}

// ─────────────────────────────────────────────────────────────────────────────
// 依赖规则：配置期强制，任何构建任务都会先过这一关。
// 违反 = 整个工程无法编译，不是警告。这是"不再耦合"的唯一可靠实现方式。
//
// 例外开关（仅在排查 Gradle 自身问题时使用）：-Plite.skipRules=true
// ─────────────────────────────────────────────────────────────────────────────
val forbidden: Map<String, Set<String>> = mapOf(
    // core 是最底层：不得依赖任何本工程模块
    ":core" to setOf(":data", ":uikit", ":feature-catalog", ":feature-detail", ":feature-player", ":app"),
    // 设计系统不依赖业务
    ":uikit" to setOf(":data", ":feature-catalog", ":feature-detail", ":feature-player", ":app"),
    // 三个 feature 之间零依赖，且不得直接碰 data 的实现
    ":feature-catalog" to setOf(":feature-detail", ":feature-player", ":data", ":app"),
    ":feature-detail" to setOf(":feature-catalog", ":feature-player", ":data", ":app"),
    ":feature-player" to setOf(":feature-catalog", ":feature-detail", ":data", ":app"),
    // data 是实现层，不反向依赖任何上层
    ":data" to setOf(":uikit", ":feature-catalog", ":feature-detail", ":feature-player", ":app"),
)

gradle.projectsEvaluated {
    if (project.hasProperty("lite.skipRules")) return@projectsEvaluated

    val violations = mutableListOf<String>()
    rootProject.subprojects.forEach { p ->
        val banned = forbidden[p.path] ?: return@forEach
        val declared = p.configurations
            .filter { it.name.startsWith("implementation") || it.name.startsWith("api") || it.name.startsWith("compileOnly") }
            .flatMap { c -> c.dependencies.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>() }
            .map { it.path }
            .toSet()
        banned.intersect(declared).forEach { hit -> violations += "${p.path} 不允许依赖 $hit" }
    }

    if (violations.isNotEmpty()) {
        throw GradleException(
            buildString {
                appendLine("依赖规则被违反（见 settings.gradle.kts 与 docs 的模块图）：")
                violations.forEach { appendLine("  ✕ $it") }
                appendLine("正确方向：app → feature-* / uikit / data → core")
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CI 的唯一入口。本地与流水线跑同一条命令：
//
//     ./gradlew verify --console=plain
//
// 它把"每个阶段的验收项"固定成一条依赖图：全模块单测 + 六个 Android 模块 lint +
// debug 包构建。`:core:test` 会让 `verifyJvmApiLevel` 先跑（API 23 字节码守卫），
// 所以不需要单独再挂一次。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register("verify") {
    group = "verification"
    description = "全模块单测 + 全仓 lint + assembleDebug（CI 入口）"
    dependsOn(
        ":core:test",
        ":data:testDebugUnitTest",
        ":uikit:testDebugUnitTest",
        ":feature-catalog:testDebugUnitTest",
        ":feature-detail:testDebugUnitTest",
        ":feature-player:testDebugUnitTest",
        ":app:testDebugUnitTest",
        ":data:lintDebug",
        ":uikit:lintDebug",
        ":feature-catalog:lintDebug",
        ":feature-detail:lintDebug",
        ":feature-player:lintDebug",
        ":app:lintDebug",
        ":app:assembleDebug",
    )
}
