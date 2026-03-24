plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vkturn.proxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vkturn.proxy"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        multiDexEnabled = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = true 
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("com.github.WireGuard:wireguard-android:master-SNAPSHOT")
    implementation("com.github.mwiede:jsch:0.2.17")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
}
