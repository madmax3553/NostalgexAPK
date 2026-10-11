plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// channels.json and channels-memberships.json live at the repo root and are shared
// with the tvOS app and the web tuner. Copy them into the APK instead of keeping a
// second copy in android/, so the three surfaces cannot drift (CLAUDE.md).
val channelConfigDir = layout.buildDirectory.dir("generated/channelConfig")
val syncChannelConfig by tasks.registering(Copy::class) {
    from(rootProject.file("../channels.json"))
    from(rootProject.file("../channels-memberships.json"))
    into(channelConfigDir)
}

android {
    namespace = "io.github.madmax3553.staticgroot"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.madmax3553.staticgroot"
        // Placeholder floor. Set the real device floor during the port.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
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

    sourceSets["main"].assets.srcDir(channelConfigDir)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(syncChannelConfig)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.security.crypto)

    testImplementation(libs.junit)
}
