plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.karunadavanyaa"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.karunadavanyaa"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "2.0"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // AppCompatActivity + Fragment support
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Core KTX (includes Android extensions)
    implementation("androidx.core:core-ktx:1.13.1")

    // Activity KTX — required for registerForActivityResult / ActivityResultContracts
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Material Design components
    implementation("com.google.android.material:material:1.12.0")
}
