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
