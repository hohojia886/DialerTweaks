import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.hohojia886.dialertweaks"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.hohojia886.dialertweaks"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("boolean", "ENABLE_CALL_RECORDING", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            resources.srcDirs("src/main/resources")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("org.luckypray:dexkit:2.2.0")
}

tasks.register<Zip>("backupProject") {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
    archiveFileName.set("DialerTweaks_$timestamp.zip")
    destinationDirectory.set(file("C:/Users/Administrator/Documents/GitHub/Backups"))

    from(project.rootDir) {
        exclude("**/build/**")
        exclude("**/.gradle/**")
        exclude("**/.kotlin/**")
        exclude("**/.git/**")
        exclude("**/.idea/**")
        exclude("**/.artifacts/**")
        exclude("**/local.properties")
        exclude("**/tmp/**")
    }
}

tasks.matching { it.name.startsWith("assemble") && it.name.contains("Debug") }.configureEach {
    finalizedBy("backupProject")
}
