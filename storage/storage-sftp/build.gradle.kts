plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zerotoship.foldex.storage.sftp"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(projects.core.coreCommon)
    implementation(projects.core.coreModel)
    implementation(projects.core.coreData)

    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // sshj は P5 で追加: com.hierynomus:sshj:0.39.0

    testImplementation(libs.junit)
    androidTestImplementation(libs.test.ext.junit)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
