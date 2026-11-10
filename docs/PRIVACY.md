# Privacy policy / Política de privacidad

[English](#english) · [Español](#español)

## English

MeshCentral Client is an app for technicians who manage their own MeshCentral server. It has no server of its own: the
developer receives no data from it.

- **It connects only to the MeshCentral server the user enters**, to sign in and to manage that server's devices, as the
  user asks. Everything goes over an encrypted connection (HTTPS/WSS). What that server keeps is up to whoever runs it.
- **What stays on the phone:** the addresses of the servers the user saved, the user names, and, only if the user asks
  to remember it, the password, encrypted with a key held by the Android Keystore. With single sign-on, and only if
  the user asks, a MeshCentral login token instead of the password. If the user turns on **fingerprint sign-in**, that
  password or token is encrypted with a key that Android only unlocks after a strong biometric check (fingerprint or
  face); the app never receives the fingerprint or the face, only Android's yes or no. A certificate the user chose to
  trust is kept by its fingerprint. None of this is copied to backups or to other devices.
- **Deleting it:** "Forget" on a saved server removes it from the phone; uninstalling the app removes everything.
- There is no telemetry, analytics, advertising or advertising ID, and no account with the developer.

Questions: open an issue at https://github.com/Nebur692/meshcentral-android-client/issues.

---

## Español

Cliente MeshCentral es una app para técnicos que gestionan su propio servidor MeshCentral. No tiene servidor propio: el
desarrollador no recibe ningún dato de ella.

- **Solo se conecta al servidor MeshCentral que escribe el usuario**, para entrar y gestionar los equipos de ese
  servidor, cuando el usuario lo pide. Todo va por una conexión cifrada (HTTPS/WSS). Lo que guarde ese servidor depende
  de quien lo gestiona.
- **Lo que se queda en el teléfono:** las direcciones de los servidores que el usuario guardó, los nombres de usuario y,
  solo si pide recordarla, la contraseña, cifrada con una clave que guarda el almacén de claves de Android. Con el
  inicio de sesión único, y solo si el usuario lo pide, un token de entrada de MeshCentral en lugar de la contraseña.
  Si el usuario activa **entrar con huella**, esa contraseña o ese token se cifran con una clave que Android solo abre
  tras una comprobación biométrica fuerte (huella o cara); la app nunca recibe la huella ni la cara, solo el sí o el no
  de Android. Un certificado en el que el usuario decidió confiar se guarda por su huella. Nada de esto va a las copias
  de seguridad ni a otros dispositivos.
- **Borrarlo:** «Olvidar» en un servidor guardado lo quita del teléfono; desinstalar la app lo borra todo.
- No hay telemetría, analítica, publicidad ni ID de publicidad, ni cuenta con el desarrollador.

Dudas: abre una incidencia en https://github.com/Nebur692/meshcentral-android-client/issues.
