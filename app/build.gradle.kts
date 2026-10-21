plugins {
    alias(libs.plugins.android.application)
    id("com.google.devtools.ksp") version "2.3.12"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

android {
    namespace = "io.github.dokuendev.dokuen.plugins.dictionary.yomitan"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dokuendev.dokuen.plugins.dictionary.yomitan"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric's FileDescriptor interceptor reflects into jdk.internal.access, which
                // the JDK doesn't export to it. Without this, Robolectric tests that start the app
                // fail with an IllegalAccessException.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }

    packaging {
        resources {
            excludes += "META-INF/buildinfo.xml"
        }
    }

    lint {
        disable.addAll(
            listOf(
                // From com.ichi2.anki.lint, which is imported into this project automatically,
                // but are meant for AnkiDroid's own code and shouldn't apply here
                "DirectCalendarInstanceUsage",
                "DirectSystemCurrentTimeMillisUsage"
            )
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.kotlinx.serialization.json)

    // Jetpack Compose BOM & Core UI
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // Compose Material 3
    implementation(libs.androidx.material3)

    // Activity, Lifecycle, & ViewModel Compose Integrations
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Material XML components (required for AndroidManifest styles)
    implementation(libs.material)

    // SQLite Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Plugin SDK
    implementation(libs.dictionary)

    // AnkiDroid AddContentApi
    implementation(libs.ankidroid.api)

    // HTML parsing for the LanguagePod101 and Jisho audio sources
    implementation(libs.jsoup)

    // Unit testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.core.v150)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.inline)

    // Instrumented testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.rules)
}

tasks.register<JavaExec>("generateDebugEntries") {
    group = "development"
    description = "Generates debug_entry.json and debug_kanji_entry.json from example files"
    val testTask = tasks.named<Test>("testDebugUnitTest")
    classpath(testTask.map { it.classpath })
    mainClass.set("io.github.dokuendev.dokuen.plugins.dictionary.yomitan.DebugGenerator")
    workingDir = rootProject.projectDir
}
