# Plan

[English](#english) · [Español](#español)

## English

A native Android app for the technician, for any MeshCentral server: the sibling of
[MeshCentral Windows Client](https://github.com/Nebur692/meshcentral-windows-client), with the same protocol and the same
error keys. Kotlin, Jetpack Compose and Material 3; Android 10 and newer, using what each newer Android adds (themed icon
and per-app language on 13, predictive back on 14), in the brand's own colours, as the Windows app. Distributed on Google Play.

| # | Phase | State |
|---|-------|-------|
| 1 | **Sign in** (password, two-factor by app or email, certificate pinned by its fingerprint, **fingerprint sign-in**), saved servers (password only if asked, encrypted with Android's keystore), **live device list** with search and filters, the **full device sheet** (connection, security, system, hardware, network, notes, events), account | **done** (0.3.0) |
| 2 | **Terminal** (shell, cmd, PowerShell) with the keys a phone lacks, and **files** (browse, upload, download with progress) | **done** (0.4.0) |
| 3 | **Remote desktop** by touch: direct touch or touchpad, keyboard, Ctrl+Alt+Del, clipboard (text) | **done** (0.5.0; clipboard 0.6.0) |
| 4 | **Device actions** (wake, sleep, restart, power off, message, run commands, edit, uninstall, remove), **single sign-on**, notifications from the server | **done** (0.3.0; notifications 0.6.0) |
| 5 | Groups, users, server events, port mapping and SSH | **done** (0.7.0 administration, 0.8.0 port mapping and SSH) |
| 6 | Google Play: signing, store listing, closed test, then production | **in closed test** |

## Español

Una app nativa de Android para el técnico, para cualquier servidor MeshCentral: la hermana de
[MeshCentral Windows Client](https://github.com/Nebur692/meshcentral-windows-client), con el mismo protocolo y las mismas
claves de error. Kotlin, Jetpack Compose y Material 3; Android 10 o más nuevo, usando lo que trae cada versión (icono
temático e idioma por app en la 13, «Atrás» predictivo en la 14), con los colores de la marca, como la app de Windows. Se reparte por Google Play.

| # | Fase | Estado |
|---|------|--------|
| 1 | **Entrar** (contraseña, doble factor por app o por correo, certificado fijado por su huella, **entrar con huella**), servidores guardados (la contraseña solo si se pide, cifrada con el almacén de claves de Android), **lista de equipos en vivo** con buscador y filtros, la **ficha completa del equipo** (conexión, seguridad, sistema, hardware, red, notas, eventos), cuenta | **hecha** (0.3.0) |
| 2 | **Terminal** (shell, cmd, PowerShell) con las teclas que no tiene el móvil, y **ficheros** (explorar, subir, bajar con progreso) | **hecha** (0.4.0) |
| 3 | **Escritorio remoto** táctil: tocar donde apunto o touchpad, teclado, Ctrl+Alt+Supr, portapapeles (texto) | **hecha** (0.5.0; portapapeles 0.6.0) |
| 4 | **Acciones sobre el equipo** (despertar, suspender, reiniciar, apagar, mensaje, ejecutar órdenes, editar, desinstalar, borrar), **inicio de sesión único**, avisos del servidor | **hecha** (0.3.0; avisos 0.6.0) |
| 5 | Grupos, usuarios, sucesos del servidor, redirección de puertos y SSH | **hecha** (0.7.0 administración, 0.8.0 redirección de puertos y SSH) |
| 6 | Google Play: firma, ficha, prueba cerrada y después producción | **en prueba cerrada** |
