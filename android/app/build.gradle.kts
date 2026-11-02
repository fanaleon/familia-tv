plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Cada valor se lee de -PNOMBRE, de una variable de entorno (GitHub Actions) o del valor por defecto.
fun cfg(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: default

android {
    namespace = "com.serchu.familiatv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.serchu.familiatv"
        minSdk = 24
        targetSdk = 34
        // En GitHub Actions sube solo con cada build, así el APK nuevo siempre pisa al viejo.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"

        buildConfigField("String", "RELAY_URL", "\"${cfg("RELAY_URL", "https://CAMBIAR.up.railway.app")}\"")
        buildConfigField("String", "RELAY_TOKEN", "\"${cfg("RELAY_TOKEN", "CAMBIAR")}\"")
        buildConfigField("String", "SENDER_NAME", "\"${cfg("SENDER_NAME", "Papá")}\"")
    }

    // Firma fija: todos los builds llevan la misma firma, así se puede actualizar sin desinstalar.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
