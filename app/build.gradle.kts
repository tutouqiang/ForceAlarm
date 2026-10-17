import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.forcealarm"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.forcealarm"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // keystore 在项目根目录（不在 app 模块），用 rootProject.file 定位
            storeFile = rootProject.file("keystore/forcealarm.jks")
            // 密码从 local.properties 读（不进版本控制），找不到直接报错嗊响，不带病打包
            val localProps = Properties().apply {
                rootProject.file("local.properties").inputStream().use { load(it) }
            }
            storePassword = localProps.getProperty("FORCEALARM_STORE_PASSWORD")!!
            keyAlias = "forcealarm"
            keyPassword = localProps.getProperty("FORCEALARM_KEY_PASSWORD")!!
        }
    }
    buildTypes {
        release {
            optimization {
                enable = true
                // 【教训】packageScope 重打包会把 app 类搬进 kotlin.** 包里，
                // 破坏 JVM 包私有访问→ IllegalAccessError 闪退。纯缩小不加回
            }
            signingConfig = signingConfigs.getByName("release")
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}