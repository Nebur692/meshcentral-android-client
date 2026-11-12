plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.github.nebur692.meshclient"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.okhttp)
    // SSH to a device through its agent; Bouncy Castle for ed25519 and curve25519, which Android lacks in part.
    // SSH a un equipo por su agente; Bouncy Castle para ed25519 y curve25519, que Android no trae entero.
    implementation(libs.jsch)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    // The integration tests read the throwaway server from these (tests/server/run-integration.sh); without them they
    // are skipped. / Las pruebas de integración leen el servidor desechable de estas; sin ellas se saltan.
    listOf("MESHTEST_URL", "MESHTEST_CERT_SHA256", "MESHTEST_OTP_SECRET", "MESHTEST_GROUP_ID", "MESHTEST_DESKTOP").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
