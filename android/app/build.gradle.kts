plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.eugen.ankiaudio"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.eugen.ankiaudio"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// Build the debug APK and copy it (stable name) into <repo-root>/dist so it can be
// committed. That lets a machine with NO Android toolchain `git pull` and install it
// straight onto a phone — see ../../BUILD.md. Run on the build machine after code
// changes:  ./gradlew exportDebugApk   then commit dist/ankiaudio-debug.apk.
tasks.register<Copy>("exportDebugApk") {
    group = "distribution"
    description = "Build the debug APK and copy it to <repo>/dist/ankiaudio-debug.apk for committing."
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(File(rootProject.projectDir.parentFile, "dist"))
    rename { "ankiaudio-debug.apk" }
}