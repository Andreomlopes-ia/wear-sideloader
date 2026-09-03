# PLAN.md — Wear Sideloader: fix the greyed-out Install button and simplify the flow

> Written for an implementing agent with no prior context on this project.

## 1. What this app is

An Android **phone** app that installs APKs onto a paired **Wear OS watch** over ADB, with no PC
involved. It embeds an ADB client ([libadb-android](https://github.com/MuntashirAkon/libadb-android))
and talks to the watch's `adbd` over Wi-Fi — the approach Wear Installer 2 uses.

Context worth having: bundling a Wear APK inside a phone APK (the legacy embedded app model) has
done nothing since Wear OS 2, and there is **no public API** for a phone app to install a package on
a paired watch. ADB is the only mechanism that works. This is a sideloading tool, not a distribution
mechanism.

## 2. Baseline

- Package / `applicationId`: `pt.andreomlopes.wearsideloader`. Sources under
  `app/src/main/java/pt/andreomlopes/wearsideloader/`. Settled — do not change.
- `versionCode 20`, `versionName "0.2.0"` in `app/build.gradle`. Settled — do not change.
- **Working on real hardware**: pairing and `connect()`.
- **Broken**: everything the user does after connecting (§4).

## 3. Environment constraints — read before touching the build

This machine has **JDK 11 only**. Do not upgrade the toolchain; AGP 8+ requires JDK 17 and will not
build here.

- Gradle **7.5** (wrapper, dist cached locally), AGP **7.4.2**, Kotlin **1.8.10**
- `compileSdk`/`targetSdk` **33**, `minSdk` **24**. SDK 34 is not installed.
- XML Views + Material Components. **Not Compose.**

**Do not remove the R8 pin in the root `build.gradle`:**

```groovy
buildscript { dependencies { classpath 'com.android.tools:r8:8.2.47' } }
```

AGP 7.4.2 ships R8 4.0.52, whose D8 throws a bare `NullPointerException` on the `MethodParameters`
attribute that `libadb-android` and `spake2-android` carry on their enums. Removing the pin breaks
the build at `mergeExtDexDebug` with an error naming only the jar, never the cause.

## 4. Bugs to fix

User report: paired and connected cleanly, then the UI was confusing, and after selecting an APK the
**Install button was disabled with no explanation**. Mechanisms below are confirmed by reading the
code, not inferred from the symptom.

1. **Connection state is derived from the wrong thing.** `MainViewModel.kt:170` sets
   `CONNECTED`/`DISCONNECTED` from the *block's return value*, conflating "this command succeeded"
   with "the socket is alive". A throwing `listPackages()` hits the catch, returns `false`, state
   becomes `DISCONNECTED`, and `MainActivity.kt:86` (`installButton.isEnabled = connected`) greys
   out Install. **This is the reported bug**: one failed command disables Install until a manual
   reconnect, while ADB is still perfectly connected.
2. **The watchdog tears down a live connection.** `MainViewModel.kt:182` calls `disconnect()` on any
   timeout. Breaking a parked reader is genuinely necessary, but today one slow command costs the
   whole session.
3. **Duplicate IP fields.** `pairHost` (layout:56) and `connectHost` (layout:105) must hold identical
   values. `MainActivity.kt:27` pre-fills `pairHost` from `lastHost`, which is only written after a
   *successful connect* (`MainViewModel.kt:56`) — so it is empty on first run, exactly when needed.
4. **Disabled buttons never say why** (`MainActivity.kt:81-88`). A grey button is a dead end.
5. **The picked APK URI is not persisted.** `MainViewModel.kt:88` opens a non-persisted SAF URI;
   after process death `openInputStream` throws `SecurityException`.

## 5. The open question this version must answer

Whether the `shell:` stream path works against a real watch is **unknown**. It has never been
verified anywhere — the emulator structurally cannot test it (§9), and the user has not yet run
"List installed apps" on hardware. `connect()` is confirmed; everything downstream is unproven.

So: make the app robust either way, and make the logs settle it on the next real-hardware run.

## 6. Design: fewer steps, no dead ends

Collapse four numbered cards into three, and make the primary action always actionable.

- **Watch** — one `watchHost` field (shared by pairing and connecting) plus connection port,
  `Connect`, and `Find watch` (mDNS discovery that **fills the fields** rather than only connecting;
  reuse the existing `autoConnect` path in `MainViewModel.kt:65`). Pairing collapses behind a
  "Not paired yet?" expander that reuses the same IP field and adds only pairing port + code.
- **Install** — `Choose APK`, then one primary `Install on watch`, **enabled whenever an APK is
  selected**; it reconnects first if the link is down. Helper text below states what will happen
  ("Will connect to 192.168.1.42:5555 first") or what is missing.
- **Tools** (collapsed) — list packages, uninstall. **Log** unchanged, plus timings.

Guiding rule: **never show a disabled control without a reason beside it.**

## 7. Changes

### 7.1 Derive connection state from the socket — `MainViewModel.kt`
Change `runAdb`'s block type from `(AdbManager) -> Boolean` to `(AdbManager) -> Unit`. After the
block returns, set state from `manager.isConnected` (inherited from `AbsAdbConnectionManager`)
rather than a return value. A command that fails without hanging then leaves the connection intact.

Keep the timeout path calling `disconnect()` — a parked reader must be broken — but log it as
"Connection dropped; the next action will reconnect automatically."

### 7.2 Auto-reconnect — `MainViewModel.kt`
Add `ensureConnected(manager): Boolean` — true if `manager.isConnected`; otherwise reconnect from the
saved host/port, logging "Reconnecting to X:Y…"; false with a clear message when nothing is saved.
Call it at the top of `install`, `listPackages` and `uninstall`. This is what actually removes the
dead end the user hit.

### 7.3 One IP field — `activity_main.xml`, `MainActivity.kt`
Delete `pairHost`; rename `connectHost` → `watchHost` and have the pairing block read it. Persist the
IP on a successful **pair** too, not only on connect, so it survives the first run.

### 7.4 Explain every disabled state — `MainActivity.kt`
Rewrite `render()` (`:68-89`) around an explicit reason string. Install stays enabled once an APK is
chosen. Add a helper `TextView` beneath the primary action.

### 7.5 Persist the APK selection — `MainViewModel.kt`, `MainActivity.kt`
Call `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` in the picker callback
(`MainActivity.kt:17-19`), store the URI string in prefs, restore on init, re-validating that it
still resolves and clearing the selection if not.

### 7.6 Shell fallback and diagnostics — `AdbInstaller.kt`
`shell()` currently opens `shell:$command` (`:12-15`). If `shell:` yields empty output, retry once
over `exec:` and log which transport produced the result. Log elapsed milliseconds for every ADB
call. Drop the quick-command timeout 30s → 15s so failures surface sooner; leave install at 600s.

This logging is the real deliverable of §5 — the fallback is cheap insurance.

## 8. Files

| File | Change |
| --- | --- |
| `.../wearsideloader/MainViewModel.kt` | State derivation, `ensureConnected`, URI persistence, timeouts |
| `.../wearsideloader/MainActivity.kt` | Single host field, reason-driven `render()`, persistable URI |
| `.../wearsideloader/AdbInstaller.kt` | `exec:` fallback in `shell()`, timing logs |
| `.../res/layout/activity_main.xml` | Three cards, one IP field, expanders, helper text |
| `.../res/values/strings.xml` | New reason/helper strings |

`AdbManager.kt` needs no change — `isConnected` is inherited.

## 9. Verification

### Emulator (API 30) — regression checks only
Drive the UI with `adb shell input tap`, using coordinates from `uiautomator dump`.

1. `./gradlew assembleDebug`, install, launch. Confirm the three-card layout, and that Install is
   enabled as soon as an APK is picked, with helper text naming the target.
2. Connect to `10.0.2.2:5555`, then tap "List installed apps". It **will** time out. The regression
   check is that **Install remains actionable afterwards** instead of greying out.
3. Force-stop and relaunch — IP, port and APK selection should all be restored.

**The emulator cannot validate `shell:` or the streaming install.** Its `adbd` speaks only over the
qemu pipe (`adb tcpip` produces no guest TCP listener), so the only reachable endpoint is the
emulator's host-side transport port, which completes CNXN/AUTH but does not carry stream traffic for
a second client. Do not read a stream stall there as an app bug. The host `adb` server also contends
for that transport — run `adb kill-server` first when testing connects.

### Real hardware — the only place §5 resolves
4. Pair, connect, choose `/sdcard/Download/WearHello-1.0-debug.apk` (a minimal Wear OS test app
   already on the user's phone; source in `../wearhello`), Install. Confirm "Wear Hello" appears in
   the watch launcher.
5. Capture the log either way. The timing lines and the `shell:`/`exec:` marker are what answer
   whether the stream path works.

### Environment gotchas
- `adb push` from Git Bash: the device path needs `MSYS_NO_PATHCONV=1` **and** the local path must be
  Windows-style (`C:/...`), because `adb.exe` cannot read `/c/...`.
- `adb shell screencap -p /sdcard/x.png` gets its path mangled too; use
  `adb exec-out screencap -p > file.png`.

## 10. Risks

- **The `exec:` fallback may not help.** If `shell:` fails on hardware for a protocol reason, `exec:`
  may fail identically. The logging matters more than the fallback.
- **Auto-reconnect can mask a genuinely dead watch** by silently retrying. Bound it to one reconnect
  attempt per user action, and always log the attempt.
