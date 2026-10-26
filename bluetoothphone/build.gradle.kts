plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.bluetooth.phone"
    compileSdk {
        version = release(34)
    }

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // StateFlow/SharedFlow 出现在公共 API 中，需作为 api 传递给使用方。
    api(libs.kotlinx.coroutines.android)
}
