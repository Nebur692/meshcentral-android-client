# kotlinx.serialization and OkHttp ship their own consumer rules. / Traen sus propias reglas.

# JSch (SSH) builds its ciphers, key exchanges and signatures by name (JSch.getConfig + Class.forName): without this R8
# removes them and SSH fails only in the release build. / JSch crea sus algoritmos por nombre: sin esto R8 los quita y el
# SSH falla solo en la versión publicada.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
