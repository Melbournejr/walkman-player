plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val walkmanVersion = "11.9.0"
val walkmanVersionCode = 1190
val githubRepoSlug = "Melbournejr/walkman-player"

val signingEnvironment = listOf(
    "WALKMAN_KEYSTORE_PATH", "WALKMAN_STORE_PASSWORD", "WALKMAN_KEY_ALIAS", "WALKMAN_KEY_PASSWORD"
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = signingEnvironment.values.all { !it.isNullOrBlank() }
require(hasReleaseSigning || signingEnvironment.values.all { it.isNullOrBlank() }) {
    "Provide all four WALKMAN_* signing variables or leave them unset for an unsigned build."
}

android {
    namespace = "com.sony.walkmanpro"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.sony.walkmanpro"
        minSdk = 26
        targetSdk = 34
        versionCode = walkmanVersionCode
        versionName = walkmanVersion
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepoSlug\"")
        resValue("string", "app_name", "Walkman $walkmanVersion")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("walkmanRelease") {
                storeFile = file(signingEnvironment.getValue("WALKMAN_KEYSTORE_PATH")!!)
                storePassword = signingEnvironment.getValue("WALKMAN_STORE_PASSWORD")
                keyAlias = signingEnvironment.getValue("WALKMAN_KEY_ALIAS")
                keyPassword = signingEnvironment.getValue("WALKMAN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            // Keep release JNI entry points and callbacks intact until a minified build is certified.
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("walkmanRelease")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "**/libavcodec.so",
                "**/libavformat.so",
                "**/libavutil.so",
                "**/libswresample.so"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
