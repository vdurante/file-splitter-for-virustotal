import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Release signing key, kept out of git (see README → Release builds). Without this file, release
// builds are produced unsigned.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.vdurante.filesplitter"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.vdurante.filesplitter"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Shrink and optimize the whole app. (The template's packageScope limited this to
            // libraries, which produced an IllegalAccessError at startup.)
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // Compose previews in Android Studio.
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ./gradlew publishGithubRelease --console=plain: builds the signed release APK and publishes it
// on GitHub (see scripts/release.sh). Interactive, so run it from a terminal.
tasks.register<Exec>("publishGithubRelease") {
    group = "publishing"
    description = "Builds the signed release APK and publishes it as a GitHub release (needs gh)."
    dependsOn("assembleRelease")
    workingDir = rootDir
    commandLine("bash", "scripts/release.sh", "--no-build")
    doFirst {
        logger.lifecycle("Tip: add --console=plain (or run scripts/release.sh) so Gradle's progress bar doesn't cover the questions.")
    }
    standardInput = System.`in`
    notCompatibleWithConfigurationCache("Asks questions on the terminal")
}
