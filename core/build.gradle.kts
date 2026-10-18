// Models, API client, token store interface. Pure Kotlin/JVM: no Android imports (SPEC-ANDROID §2).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Lets `./gradlew lint` analyze this module too.
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    // runTest helpers (advanceTimeBy, runCurrent, …) are still marked experimental.
    sourceSets.test { languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi") }
    // SerialDescriptor.serialName: used only to name types in decoding-error messages.
    compilerOptions.optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    // runTest helpers (advanceTimeBy, runCurrent, …) are still marked experimental.
    sourceSets.test { languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi") }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // Contract tests run only when MP_BASE_URL points at a seeded backend (CI `contract` job).
    val baseUrl = System.getenv("MP_BASE_URL") ?: ""
    environment("MP_BASE_URL", baseUrl)
    // An input, so results cached without a backend (contract tests skipped) are never reused with one.
    inputs.property("mpBaseUrl", baseUrl)
}
