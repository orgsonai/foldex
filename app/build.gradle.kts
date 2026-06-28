// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

// 正式リリース署名用の資格情報 (リポジトリ直下 keystore.properties・gitignore 済み)。
// 無いマシンでは debug 鍵にフォールバックするので、共有開発に支障は出ない。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.zerotoship.foldex"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zerotoship.foldex"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // debug ビルドは Compose ランタイムチェック・JIT 未温め等で release の数倍重い。
            // 起動時の体感を評価する場合は assembleRelease で確認する。
            // release (com.zerotoship.foldex) と共存・区別できるよう、パッケージと
            // バージョン名・表示名 (src/debug/res の app_name) をずらす。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            // R8/ProGuard は依存ライブラリ (SMB/SFTP/FTP/BouncyCastle/Sardine) のルール整備が
            // 必要なため P9 (リリース準備) で有効化する。当面は OFF。
            isMinifyEnabled = false
            isDebuggable = false
            // keystore.properties があれば正式鍵で署名、無ければ debug 鍵にフォールバック。
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_stability.conf")
    )
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // P7: サムネ/ビューア/音声プレーヤー/Markdown/文字コード判定
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.linkify)
    implementation(libs.juniversalchardet)

    // ZIP 圧縮/解凍 + AES-256 パスワード暗号化 (P7)。
    implementation(libs.zip4j)

    // 大容量テキスト編集向け: Canvas 描画 + 仮想化のコードエディタ (P7 ポリッシュ)。
    implementation(libs.sora.editor)

    // HOME タイルのドラッグ並び替え (LazyVerticalGrid 対応)。
    implementation(libs.reorderable)

    // Application.onCreate で SFTP (Apache MINA SSHD) より先に BC を登録するため app から直接参照する。
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(projects.core.coreCommon)
    implementation(projects.core.coreModel)
    implementation(projects.core.coreData)
    implementation(projects.storage.storageLocal)
    implementation(projects.storage.storageSmb)
    implementation(projects.storage.storageSftp)
    implementation(projects.storage.storageFtp)
    implementation(projects.storage.storageWebdav)
    implementation(projects.server)
    implementation(projects.sync)
}
