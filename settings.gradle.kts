// The app (:app) and its protocol core (core/, a build of its own that needs no Android SDK, so its tests run against a
// real MeshCentral anywhere). / La app (:app) y su núcleo de protocolo (core/, una compilación aparte que no necesita el
// SDK de Android, así que sus pruebas corren contra un MeshCentral de verdad en cualquier sitio).
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
rootProject.name = "meshcentral-android-client"
includeBuild("core")
include(":app")
