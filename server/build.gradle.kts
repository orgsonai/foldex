plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zerotoship.foldex.server"
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

    // P6 で追加予定:
    // Apache MINA SSHD: org.apache.sshd:sshd-sftp:2.13.2
    // Apache FtpServer: org.apache.ftpserver:ftpserver-core:1.2.0
    // Argon2id: de.mkammerer:argon2-jvm:2.11

    testImplementation(libs.junit)
    androidTestImplementation(libs.test.ext.junit)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
