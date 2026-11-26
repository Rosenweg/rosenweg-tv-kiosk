// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Rosenweg-Module (ch.rosenweg4303:updater) aus GitHub Packages von commons-rosenweg.
// Auch oeffentliche Maven-Pakete brauchen dort eine Anmeldung: gpr.user/gpr.key in
// ~/.gradle/gradle.properties (gpr.key = Token mit read:packages, z. B. `gh auth token`)
// oder GPR_TOKEN/GITHUB_TOKEN in der Umgebung. Ohne Anmeldung: mavenLocal() als Rueckfall
// (in commons-rosenweg `./gradlew publishToMavenLocal`).
val gprUser: String? = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
val gprKey: String? = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GPR_TOKEN") ?: System.getenv("GITHUB_TOKEN")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (!gprKey.isNullOrBlank()) {
            maven {
                name = "RosenwegCommons"
                url = uri("https://maven.pkg.github.com/Rosenweg/commons-rosenweg")
                credentials {
                    username = gprUser ?: "token"
                    password = gprKey
                }
                content { includeGroup("ch.rosenweg4303") }
            }
        }
        mavenLocal {
            content { includeGroup("ch.rosenweg4303") }
        }
    }
}

rootProject.name = "rosenweg-tv-kiosk"
include(":app")
