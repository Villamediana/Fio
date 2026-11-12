import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Signing lives outside the repo, in ~/keystores: the release key (the GitHub build; the phones only take
// an update signed by it) and the upload key (what goes to Google Play, which signs the app itself).
fun keystoreProps(vararg candidates: File) = Properties().apply {
    candidates.firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}
val signingProps = keystoreProps(rootProject.file("keystore.properties"), File(System.getProperty("user.home"), "keystores/novelo-keystore.properties"))
val uploadProps = keystoreProps(File(System.getProperty("user.home"), "keystores/novelo-upload.properties"))

// The on-device model (LFM3 through llama.cpp, app/src/main/cpp). Built only with -Pnovelo.llama=<dir>,
// a llama.cpp checkout that lives just for the build (tools/release.sh fetches it and deletes it).
val llamaDir = (findProperty("novelo.llama") as String?)?.takeIf { it.isNotBlank() }?.let { File(it) }
val withNative = llamaDir != null && File(llamaDir, "CMakeLists.txt").exists()

// The Novelo's own intelligence goes through the Novelo's server (server/): the app holds no key,
// only where the server is (novelo.server in gradle.properties; empty = no Novelo intelligence).
val noveloServer: String = (findProperty("novelo.server") as String?)?.trim().orEmpty()

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.villamediana.novelo"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.villamediana.novelo"
        minSdk = 29
        targetSdk = 36
        versionCode = 39
        versionName = "1.26.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_LOCAL_ENGINE", withNative.toString())
        buildConfigField("String", "NOVELO_SERVER", "\"" + noveloServer + "\"")
        // Phones only (ML Kit's OCR alone ships ~11 MB per ABI).
        ndk { abiFilters += listOf("arm64-v8a") }
        if (withNative) {
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DLLAMA_DIR=${llamaDir!!.absolutePath}", "-DANDROID_STL=c++_shared")
                    cppFlags += listOf("-std=c++17")
                }
            }
        }
    }

    if (withNative) {
        ndkVersion = "29.0.14206865"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    signingConfigs {
        for ((name, props) in listOf("release" to signingProps, "upload" to uploadProps)) {
            if (props.getProperty("storeFile") == null) continue
            create(name) {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    // Two builds of the same app. github: its own updates from the GitHub releases (src/github), signed
    // with the release key. play: no updater (Play updates it, and an app from Play may not update itself),
    // signed with the upload key; it goes to Play as an App Bundle (bundlePlayRelease).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            signingConfig = signingConfigs.findByName("release")
        }
        create("play") {
            dimension = "store"
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Same choice as FreeSong: the release runs the bytecode the tests run.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never the debug key: each build signs with its own (productFlavors above).
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("pt", "es", "en")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/README.md",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
        jniLibs { useLegacyPackaging = false }
    }

    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                )
                it.maxHeapSize = "3g"
                it.systemProperty("novelo.shots", System.getenv("NOVELO_SHOTS") ?: "")
                it.testLogging { events("failed", "skipped"); showStandardStreams = true }
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

gradle.taskGraph.whenReady {
    fun wants(flavor: String) = allTasks.any { it.project == project && it.name.contains("${flavor}Release") && (it.name.startsWith("assemble") || it.name.startsWith("package") || it.name.startsWith("bundle")) }
    if (wants("Github") && signingProps.getProperty("storeFile") == null) {
        throw GradleException("Release signing keystore missing: ~/keystores/novelo-keystore.properties")
    }
    if (wants("Play") && uploadProps.getProperty("storeFile") == null) {
        throw GradleException("Play upload keystore missing: ~/keystores/novelo-upload.properties")
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.androidx.core)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    // Google Play Billing: só a versão da Play (a do GitHub simula a compra).
    "playImplementation"(libs.billing.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.text)
    implementation(libs.exif)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
