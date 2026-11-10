# Plan

[English](#english) · [Español](#español)

## English

A native Android app for the technician, for any MeshCentral server: the sibling of
[MeshCentral Windows Client](https://github.com/Nebur692/meshcentral-windows-client), with the same protocol and the same
error keys. Kotlin, Jetpack Compose and Material 3; Android 10 and newer, using what each newer Android adds (Material You
on 12, themed icon and per-app language on 13, predictive back on 14). Distributed on Google Play.

| # | Phase | State |
|---|-------|-------|
| 1 | **Sign in** (password, two-factor by app or email, certificate pinned by its fingerprint), saved servers (password only if asked, encrypted with Android's keystore), **live device list** with search and filters, the device's sheet, account | **in progress** |
| 2 | **Terminal** (shell, cmd, PowerShell) with the keys a phone lacks, and **files** (browse, upload, download with progress) | |
| 3 | **Remote desktop** by touch: direct touch or touchpad, keyboard, Ctrl+Alt+Del, clipboard | |
| 4 | **Device actions** (wake, restart, power off, message, run commands), **single sign-on**, notifications | |
| 5 | Groups, users, server events, port mapping and SSH | |
| 6 | Google Play: signing, store listing, closed test, then production | |

## Español

Una app nativa de Android para el técnico, para cualquier servidor MeshCentral: la hermana de
[MeshCentral Windows Client](https://github.com/Nebur692/meshcentral-windows-client), con el mismo protocolo y las mismas
claves de error. Kotlin, Jetpack Compose y Material 3; Android 10 o más nuevo, usando lo que trae cada versión (Material
You en la 12, icono temático e idioma por app en la 13, «Atrás» predictivo en la 14). Se reparte por Google Play.

| # | Fase | Estado |
|---|------|--------|
| 1 | **Entrar** (contraseña, doble factor por app o por correo, certificado fijado por su huella), servidores guardados (la contraseña solo si se pide, cifrada con el almacén de claves de Android), **lista de equipos en vivo** con buscador y filtros, ficha del equipo, cuenta | **en marcha** |
| 2 | **Terminal** (shell, cmd, PowerShell) con las teclas que no tiene el móvil, y **ficheros** (explorar, subir, bajar con progreso) | |
| 3 | **Escritorio remoto** táctil: tocar donde apunto o touchpad, teclado, Ctrl+Alt+Supr, portapapeles | |
| 4 | **Acciones sobre el equipo** (encender, reiniciar, apagar, mensaje, ejecutar órdenes), **inicio de sesión único**, avisos | |
| 5 | Grupos, usuarios, sucesos del servidor, redirección de puertos y SSH | |
| 6 | Google Play: firma, ficha, prueba cerrada y después producción | |
