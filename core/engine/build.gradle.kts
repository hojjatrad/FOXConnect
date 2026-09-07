plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.foxconnect.core.engine"
    compileSdk = 37

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:parser"))
    implementation(project(":core:storage"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)

    // The typed JNI boundary must compile against the exact pinned gomobile API.
    // CI restores the official GPL-3.0 AAR and verifies libs/libbox.aar.sha256.
    val libbox = file("libs/libbox.aar")
    require(libbox.isFile) { "Restore the pinned libbox AAR before building" }
    // The library compiles against libbox, while the final application packages it.
    // This avoids nesting a local AAR inside another AAR, which AGP rejects.
    compileOnly(files(libbox))
}
