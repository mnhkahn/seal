plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}
android {
    namespace = "com.cyeam.seal"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cyeam.seal"
        minSdk = 28
        targetSdk = 35
        versionCode = providers.gradleProperty("releaseVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("releaseVersionName").orNull ?: "1.0.0"
        buildConfigField("String", "OCR_URL", "\"https://www.cyeam.com/mo/api/ocr?top_k=10\"")
        buildConfigField("String", "APP_UPDATE_URL", "\"https://www.cyeam.com/api/apps/seal/update\"")
        androidResources { generateLocaleConfig = true }
    }
    signingConfigs {
        create("release") {
            (System.getenv("SEAL_KEYSTORE_FILE") ?: System.getenv("KEYSTORE_FILE"))?.let { storeFile = file(it) }
            storePassword = (System.getenv("SEAL_KEYSTORE_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD"))
            keyAlias = (System.getenv("SEAL_KEY_ALIAS") ?: System.getenv("KEY_ALIAS"))
            keyPassword = (System.getenv("SEAL_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD"))
        }
    }
    buildTypes {
        release {
            if ((System.getenv("SEAL_KEYSTORE_FILE") ?: System.getenv("KEYSTORE_FILE")) != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
