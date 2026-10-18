plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mekari.project"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mekari.project"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the test launch flags (LaunchFlags.read).
        buildConfig = true
    }

    testOptions {
        // Robolectric needs the merged resources for Compose UI tests on the JVM.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Errors fail the build; warnings stay visible in the report.
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

kotlin {
    jvmToolchain(17)
}

androidComponents {
    // Robolectric Compose tests launch ComponentActivity from ui-test-manifest, which is debug-only (it
    // must never ship in the release app). Release unit tests would only duplicate the debug ones.
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

composeCompiler {
    // :core models are immutable but can't carry @Immutable (no Compose in :core): rows of them skip
    // recomposition when unchanged (ARCHITECTURE.md, TaskCache).
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}

dependencies {
    implementation(project(":feature"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.browser)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
