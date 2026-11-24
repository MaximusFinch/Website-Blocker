plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.siteblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.siteblocker"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "1.10"
    }

    // The signing key is NOT stored in this repository. The build reads it from
    // environment variables, which GitHub Actions fills from repository secrets.
    // Without them (e.g. a local build) the default debug key is used.
    val keystorePath = System.getenv("SIGNING_KEYSTORE")
    val keystorePassword = System.getenv("SIGNING_PASSWORD")
    val hasReleaseKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseKey) {
            create("siteblocker") {
                storeFile = file(keystorePath!!)
                storeType = "pkcs12"
                storePassword = keystorePassword
                keyAlias = "siteblocker"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("siteblocker")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "siteblocker" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
