import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val walkmanVersion = "11.0.3"
val walkmanVersionCode = 1103

val fixedKeystoreFile = file("walkman-permanent.jks")
if (!fixedKeystoreFile.exists()) {
    try {
        val javaHome = System.getProperty("java.home")
        val keytoolExe = if (System.getProperty("os.name").lowercase().contains("win")) {
            "$javaHome\\bin\\keytool.exe"
        } else {
            "$javaHome/bin/keytool"
        }
        ProcessBuilder(
            keytoolExe, "-genkeypair", "-v",
            "-keystore", fixedKeystoreFile.absolutePath,
            "-alias", "walkman_ permanent_key",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "36500",
            "-storepass", "walkman2026", "-keypass", "walkman2026",
            "-dname", "CN=SonyWalkman, OU=SignatureSeries, O=Sony, L=Tokyo, S=Tokyo, C=JP"
        ).start().waitFor()
    } catch (_: Exception) {}
}

android {
    namespace = "com.sony.walkmanpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sony.walkmanpro"
        minSdk = 26
        targetSdk = 34
        versionCode = walkmanVersionCode
        versionName = walkmanVersion
        setProperty("archivesBaseName", "Walkman $walkmanVersion")
    }

    signingConfigs {
        if (fixedKeystoreFile.exists()) {
            create("permanentSignature") {
                storeFile = fixedKeystoreFile
                storePassword = "walkman2026"
                keyAlias = "walkman_ permanent_key"
                keyPassword = "walkman2026"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (fixedKeystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("permanentSignature")
            }
        }
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl?.outputFileName = "Walkman ${walkmanVersion}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}

