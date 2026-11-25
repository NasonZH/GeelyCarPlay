plugins {
    id("com.android.application")
}

// 基线版本号同时用于应用版本和 APK 输出文件名
val appVersionName = "1.0.1"

android {
    namespace = "com.geely.carplay.ui"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.carplay"
        minSdk = 18
        targetSdk = 18
        multiDexEnabled = true
        versionCode = 2
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
            output.outputFileName.set("CarPlay-v$appVersionName.apk")
        }
    }
}
