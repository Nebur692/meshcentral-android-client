<div align="center">

# meshcentral-android-client

*A native Android app for technicians, for any MeshCentral server: sign in, see every device live and connect*

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Build](https://github.com/Nebur692/meshcentral-android-client/actions/workflows/build.yml/badge.svg)](https://github.com/Nebur692/meshcentral-android-client/actions/workflows/build.yml)

🇬🇧 [English](#english) · 🇪🇸 [Español](#español) · [Privacy policy](docs/PRIVACY.md)

</div>

---

## English

The Android sibling of [MeshCentral Windows Client](https://github.com/Nebur692/meshcentral-windows-client): it talks to
[MeshCentral](https://github.com/Ylianst/MeshCentral) through the same channels as the official website, so it works with
**any** installation, with no plugin and no server change. Kotlin, Jetpack Compose and Material 3, for Android 10 and newer.

> Unofficial project, not affiliated with the MeshCentral authors. What it does, phase by phase: [the plan](docs/PLAN.md).

### Get it

**Download it from [Google Play](https://play.google.com/store/apps/details?id=io.github.nebur692.meshclient)** ("MeshCentral Client"): it updates by itself. It is in closed testing for
now, so Google Play shows it only to the testers until it opens to everyone. The code here is to read and to build.

### Build and test

```bash
./gradlew :app:assembleDebug                 # needs the Android SDK (ANDROID_HOME)
tests/server/run-integration.sh              # needs Docker: the protocol core against a real throwaway MeshCentral + Linux agent
```

### License

[Apache 2.0](LICENSE).

---

## Español

La hermana Android de [MeshCentral Windows Client](https://github.com/Nebur692/meshcentral-windows-client): habla con
[MeshCentral](https://github.com/Ylianst/MeshCentral) por los mismos canales que la web oficial, así que funciona con
**cualquier** instalación, sin plugins y sin tocar el servidor. Kotlin, Jetpack Compose y Material 3, para Android 10 o
más nuevo.

> Proyecto no oficial, sin relación con los autores de MeshCentral. Qué hace, fase a fase: [el plan](docs/PLAN.md).

### Descárgala

**Descárgala de [Google Play](https://play.google.com/store/apps/details?id=io.github.nebur692.meshclient)** («Cliente MeshCentral»): se actualiza sola. Por ahora está en prueba cerrada,
así que Google Play solo la enseña a quienes la prueban hasta que se abra a todo el mundo. El código de aquí es para
leerlo y compilarlo.

### Compilar y probar

```bash
./gradlew :app:assembleDebug                 # necesita el SDK de Android (ANDROID_HOME)
tests/server/run-integration.sh              # necesita Docker: el núcleo contra un MeshCentral desechable de verdad + agente Linux
```

### Licencia

[Apache 2.0](LICENSE).
