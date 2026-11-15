package io.github.nebur692.meshclient.core

/** The tests' shortcut to the core's [Totp]. / El atajo de las pruebas al Totp del núcleo. */
internal fun totp(base32: String, atMs: Long = System.currentTimeMillis()): String = Totp.code(base32, atMs)
