plugins {
    id("com.android.application")
}

// 应用版本用于展示和发布记录
val appVersionName = "1.1.0"

android {
    namespace = "com.geely.carplay.ui"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.carplay"
        minSdk = 18
        targetSdk = 18
        multiDexEnabled = true
        versionCode = 4
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        // 车机侧载安装，不进入 Google Play，targetSdk 跟随车机固件
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("CarPlay.apk")
        }
    }
}
