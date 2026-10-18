// State holders / view models. Pure Kotlin/JVM: :core and coroutines only (SPEC-ANDROID §2).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Lets `./gradlew lint` analyze this module too.
    alias(libs.plugins.android.lint)
}

kotlin {
    jvmToolchain(17)
    // runTest helpers (advanceTimeBy, runCurrent, …) are still marked experimental.
    sourceSets.test { languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi") }
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

tasks.test {
    // Contract tests run only when MP_BASE_URL points at a seeded backend (CI `contract` job).
    val baseUrl = System.getenv("MP_BASE_URL") ?: ""
    environment("MP_BASE_URL", baseUrl)
    // An input, so results cached without a backend (contract tests skipped) are never reused with one.
    inputs.property("mpBaseUrl", baseUrl)
}
