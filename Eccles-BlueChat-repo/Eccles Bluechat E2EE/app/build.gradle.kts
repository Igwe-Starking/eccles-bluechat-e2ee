plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "starking.eccles.bluechat"
    compileSdk = 34

    defaultConfig {
        applicationId = "starking.eccles.bluechat"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            multiDexEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            multiDexEnabled = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.core)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.preference)
    implementation(libs.cardview)
    implementation(libs.recyclerview)
    implementation(libs.drawerlayout)
    implementation(libs.fragment)
    implementation(libs.viewpager)
    implementation(libs.viewpager2)
    implementation(libs.swiperefreshlayout)
    implementation(libs.transition)
    implementation(libs.multidex)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.service)
    implementation(libs.collection)

    // --- Unit tests (src/test) : run on the local JVM, no device/emulator needed ---
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    // Robolectric lets the small number of tests that touch Android SDK classes
    // (android.util.Base64/Log, SharedPreferences) run on the local JVM instead of requiring
    // an emulator, so the whole unit-test suite stays fast enough for every commit / CI run.
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)

    // --- Instrumented tests (src/androidTest) : run on a real device/emulator, needed for
    // anything backed by the Android Keystore, real SQLite, or Bluetooth stack behavior that
    // Robolectric cannot faithfully simulate. ---
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.espresso.core)
}
