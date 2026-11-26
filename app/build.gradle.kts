// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    id("com.android.application")
}

// Rosenweg-Schluessel (derselbe wie bei allen Rosenweg-Apps, sonst lehnen Updater und /apps die
// APK ab). keystore.properties hier, der Pfad in -PkeystoreProperties=… oder die von
// android-sip-rosenweg daneben. Nie einchecken.
val keystoreDatei: File? = listOfNotNull(
    providers.gradleProperty("keystoreProperties").orNull?.let { file(it) },
    rootProject.file("keystore.properties"),
    rootProject.file("../android-sip-rosenweg/keystore.properties"),
).firstOrNull { it.exists() }

android {
    namespace = "ch.rosenweg4303.kiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.rosenweg4303.kiosk"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        create("rosenweg") {
            keystoreDatei?.let { f ->
                val p = Properties().apply { f.inputStream().use { load(it) } }
                storeFile = File(f.parentFile, p.getProperty("storeFile")).takeIf { it.exists() } ?: file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreDatei != null) signingConfig = signingConfigs.getByName("rosenweg")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

base {
    archivesName = "rosenweg-kiosk"
}

dependencies {
    // Rosenweg Updater aus commons-rosenweg: "Nach Updates suchen" in den Kiosk-Einstellungen
    implementation("ch.rosenweg4303:updater:0.1.2")
}
