plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.carpet.satdemo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carpet.satdemo.app"
        // SDK 要求 Android 24+；星历 SDK 要求 26+，所以实际安装门槛是 26
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
