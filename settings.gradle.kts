// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "foldex"

include(":app")
include(":core:core-common")
include(":core:core-model")
include(":core:core-data")
include(":storage:storage-local")
include(":storage:storage-smb")
include(":storage:storage-sftp")
include(":storage:storage-ftp")
include(":storage:storage-webdav")
include(":server")
include(":sync")
