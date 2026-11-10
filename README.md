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

> Unofficial project, not affiliated with the MeshCentral authors. **In development**: see [the plan](docs/PLAN.md).

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

> Proyecto no oficial, sin relación con los autores de MeshCentral. **En desarrollo**: mira [el plan](docs/PLAN.md).

### Compilar y probar

```bash
./gradlew :app:assembleDebug                 # necesita el SDK de Android (ANDROID_HOME)
tests/server/run-integration.sh              # necesita Docker: el núcleo contra un MeshCentral desechable de verdad + agente Linux
```

### Licencia

[Apache 2.0](LICENSE).
