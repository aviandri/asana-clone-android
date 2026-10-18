// Models, API client, token store interface. Pure Kotlin/JVM: no Android imports (SPEC-ANDROID §2).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Lets `./gradlew lint` analyze this module too.
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    // SerialDescriptor.serialName: used only to name types in decoding-error messages.
    compilerOptions.optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
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
    environment("MP_BASE_URL", System.getenv("MP_BASE_URL") ?: "")
}
