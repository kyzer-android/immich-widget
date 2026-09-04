plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mathieu.immichwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mathieu.immichwidget"
        // minSdk 26 requis par EncryptedSharedPreferences (androidx.security.crypto)
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Sync périodique en arrière-plan (toutes les 6h)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Stockage chiffré de l'URL du serveur + de l'API Key Immich
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Client HTTP pour dialoguer avec l'API Immich
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines pour les appels réseau / sync asynchrones
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
