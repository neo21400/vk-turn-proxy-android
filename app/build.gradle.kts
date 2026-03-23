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
            // Включаем это, чтобы R8 исправил ошибки D8
            isMinifyEnabled = true 
            // Но отключаем удаление кода, чтобы ничего не сломать
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Используем эту версию
    implementation("com.wireguard.android:wireguard-android:1.1.0")
    
    implementation("com.github.mwiede:jsch:0.2.17")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
}
