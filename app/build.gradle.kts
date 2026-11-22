plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.roborazzi)
}

// CI sets GITHUB_RUN_NUMBER, so every CI build installs over the previous one.
val ciRunNumber: Int =
    providers
        .environmentVariable("GITHUB_RUN_NUMBER")
        .map { it.toInt() }
        .getOrElse(1)

android {
    namespace = "com.paragrudani.replyly"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.paragrudani.replyly"
        minSdk = 34
        targetSdk = 37
        versionCode = ciRunNumber
        versionName = "0.1.$ciRunNumber"
    }

    // Signing key for the APKs Parag installs. It is NOT in the repo (the repo is public): CI on main
    // and VM builds pass it in through REPLYLY_SIGNING_* environment variables. Without them (PRs,
    // forks, local tests) the debug build falls back to the SDK's per-machine debug key, and that
    // APK cannot install over Parag's app. The CI release job checks the certificate fingerprint.
    val signingStore = providers.environmentVariable("REPLYLY_SIGNING_STORE_FILE").orNull
    signingConfigs {
        if (signingStore != null) {
            create("replyly") {
                storeFile = file(signingStore)
                storeType = "pkcs12"
                storePassword = providers.environmentVariable("REPLYLY_SIGNING_PASSWORD").get()
                keyAlias = providers.environmentVariable("REPLYLY_SIGNING_KEY_ALIAS").getOrElse("replyly")
                keyPassword = providers.environmentVariable("REPLYLY_SIGNING_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            if (signingStore != null) {
                signingConfig = signingConfigs.getByName("replyly")
            }
        }
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
    }

    // Room's MigrationTestHelper reads the exported schemas from assets, and Robolectric only sees
    // the debug variant's merged assets (src/test assets are ignored). So the debug APK carries the
    // schema JSON too (a few KB, no data); release builds do not.
    sourceSets {
        getByName("debug") { assets.directories.add("$projectDir/schemas") }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        // Version-bump nags belong in a dependency PR, not in every ticket's gate.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Screenshot tests (docs/SCREENSHOTS.md). The committed PNGs are the reference;
// scripts/check.sh verifies against them, scripts/record-screenshots.sh re-records them.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.sqlcipher.android)

    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.compose.ui.test.manifest)
}
