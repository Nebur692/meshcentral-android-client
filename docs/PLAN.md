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
| 7 | What the Windows app already had: actions on several devices, own account (password, authenticator app, backup codes, security keys, login tokens), server state; native copy and paste in terminals and an automatic clipboard on the remote desktop | **done** (0.10.0) |
| 8 | Made for the phone and the tablet: **shortcuts** (the last devices on a long press of the app's icon; a device's sheet, desktop, terminal or files on the home screen), the list and the sheet **side by side** on a tablet or an open foldable, and a **physical keyboard and mouse** on the remote desktop (shortcuts such as Ctrl+C or Alt+F4, any layout's characters, three buttons and the wheel; Bluetooth, USB or Samsung DeX) | **done** (0.11.0) |
| 9 | Talking to the device: a **chat with the person at the device** (it opens in their browser; messages both ways and the "writing" sign), **agent update** (one device or several, with what each agent answers), a group's **consent and features** (notify, ask for consent or show the bar for desktop, terminal, files and registry; record, name sync, remove on disconnect or after days inactive) and the server's **recordings** (list; downloaded with a single sign-on, otherwise opened in the browser) | **in progress** (0.12.0) |

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
| 7 | Lo que ya tenía la app de Windows: acciones en varios equipos, cuenta propia (contraseña, app de autenticación, códigos de respaldo, llaves de seguridad, tokens de acceso), estado del servidor; copiar y pegar nativo en las terminales y portapapeles automático en el escritorio remoto | **hecha** (0.10.0) |
| 8 | Hecha para el móvil y la tableta: **accesos directos** (los últimos equipos con una pulsación larga en el icono de la app; la ficha, el escritorio, la terminal o los ficheros de un equipo en la pantalla de inicio), la lista y la ficha **una al lado de la otra** en una tableta o un plegable abierto, y **teclado y ratón físicos** en el escritorio remoto (atajos como Ctrl+C o Alt+F4, los caracteres de cualquier distribución, tres botones y la rueda; Bluetooth, USB o Samsung DeX) | **hecha** (0.11.0) |
| 9 | Hablar con el equipo: **chat con la persona del equipo** (se abre en su navegador; mensajes en los dos sentidos y el «escribiendo»), **actualizar el agente** (un equipo o varios, con lo que contesta cada agente), el **consentimiento y las funciones** de un grupo (avisar, pedir permiso o mostrar la barra para escritorio, terminal, ficheros y registro; grabar, sincronizar el nombre, borrar al desconectarse o tras días inactivo) y las **grabaciones** del servidor (lista; se descargan con el inicio de sesión único y, si no, se abren en el navegador) | **en curso** (0.12.0) |
