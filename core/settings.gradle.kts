// The protocol core alone: plain Kotlin, no Android. / El núcleo de protocolo solo: Kotlin puro, sin Android.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "core"
