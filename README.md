# Wear Sideloader

An Android phone app that installs APKs onto a paired Wear OS watch over ADB, with no PC involved.

## Why not just bundle the Wear APK in the phone APK?

That was the **legacy embedded app model** — the watch APK in `res/raw` plus a
`com.google.android.wearable.beta.app` meta-data entry, which Google Play Services would transfer
and install onto the paired watch automatically. It was deprecated with Wear OS 2 and finished off
in the Play Store on 2021-03-10. Bundling an APK today does nothing but enlarge the phone APK.

There is also **no public API** for a phone app to install a package on a paired watch. The Wearable
Data Layer moves bytes, but the watch side cannot install them.

The only mechanism that actually works is ADB. This app embeds an ADB client
([libadb-android](https://github.com/MuntashirAkon/libadb-android)) and talks to the watch's `adbd`
over Wi-Fi — the same approach Wear Installer 2 uses. No root, no `adb` binary, no desktop.

For real distribution, the supported route is a multi-APK / Play Store listing plus
`RemoteActivityHelper` to open the watch's Play Store from the phone. This app is a sideloading tool.

## Using it

On the watch: **Settings › Developer options** → enable **ADB debugging**, then **Wireless
debugging** → **Pair new device**. Note the IP, port and 6-digit code.

1. **Pair** — one time per watch, using the *pairing* port.
2. **Connect** — using the *connection* port from the Wireless debugging screen. It is a different
   number, and both ports are re-randomised every time Wireless debugging is toggled.
   "Find watch automatically" discovers an already-paired watch over mDNS.
3. **Choose APK** and **Install on watch**.

Requirements and gotchas:

- Watch and phone must be on the same Wi-Fi network.
- The watch screen must stay awake during pairing.
- A watch reboot drops the connection and usually requires re-pairing.
- Installs pass `-r -t`; `-t` avoids `INSTALL_FAILED_TEST_ONLY` on debug-built APKs.

## Building

```sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Toolchain is pinned to what this machine has: **JDK 11, Gradle 7.5, AGP 7.4.2, Kotlin 1.8.10,
compileSdk 33, minSdk 24**. AGP 8+ would require JDK 17.

The root `build.gradle` pins `com.android.tools:r8:8.2.47`. AGP 7.4.2 ships R8 4.0.52, whose D8
throws `NullPointerException` on the `MethodParameters` attribute that `libadb-android` and
`spake2-android` carry on their enums. Removing that pin breaks the build at `mergeExtDexDebug`.

## Layout

| File | Role |
| --- | --- |
| `AdbManager.kt` | ADB identity — RSA keypair and self-signed certificate, persisted in `filesDir` |
| `AdbInstaller.kt` | Raw ADB services: streaming install, shell, package list, uninstall |
| `MainViewModel.kt` | State machine, single ADB worker thread, log transcript |
| `MainActivity.kt` | View-binding UI and input validation |

The keypair must persist: the watch remembers this phone's public key after pairing, so
regenerating it silently invalidates every previous pairing.
