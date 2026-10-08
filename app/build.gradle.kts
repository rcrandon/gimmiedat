import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The yt-dlp release bundled in res/raw/ytdlp. build.ps1 refreshes both together.
val bundledYtDlp = "2026.08.19"

val abis = (findProperty("gimmiedat.abis") as String? ?: "arm64-v8a")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

// F-Droid builds pass -Pgimmiedat.autoUpdateYtDlp=false: F-Droid only allows fetching new code
// when the user asks for it, so those builds update yt-dlp from the about sheet button only.
val autoUpdateYtDlp = (findProperty("gimmiedat.autoUpdateYtDlp") as String?)?.toBooleanStrict() ?: true

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.gimmiedat"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gimmiedat"
        minSdk = 29
        targetSdk = 36
        // 1.0.0 = Nab 'Dat 1.0.2 renamed to Gimmie 'Dat, under its own app id so it installs beside it.
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "YTDLP_BUNDLED", "\"$bundledYtDlp\"")
        buildConfigField("boolean", "YTDLP_AUTO_UPDATE", "$autoUpdateYtDlp")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")

        ndk { abiFilters += abis }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // no keystore/ means an unsigned APK, which is what F-Droid wants to sign itself;
            // build.ps1 creates the key before its first build
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // python, ffmpeg and quickjs ship as executables inside lib/, so they must be
        // extracted to nativeLibraryDir at install time to be runnable
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,LICENSE*,NOTICE*}" }
    }
    lint {
        // the release lint pass is the heaviest step of the build; run `gradlew lint` on demand instead
        checkReleaseBuilds = false
    }
    androidResources {
        // res/raw/ytdlp is a zip; don't let aapt try to recompress it
        noCompress += "ytdlp"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
