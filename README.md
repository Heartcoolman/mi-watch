<div align="center">

<img src="docs/screenshots/icon.png" width="96" alt="HomeWrist icon" />

# HomeWrist &nbsp;<sub>小腕管家</sub>

**A third-party Mi Home client for Wear OS.**
The watch talks to Xiaomi's cloud **directly** — traffic is proxied over Bluetooth by the paired phone, so it needs neither Home Assistant nor Wi‑Fi on the watch.

[![License](https://img.shields.io/badge/license-MIT-3DA639)](LICENSE)
![Platform](https://img.shields.io/badge/Wear%20OS-6-4285F4?logo=wearos&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-33-3DDC84?logo=android&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-for%20Wear%20OS-4285F4)

English · [中文](README.zh.md)

<br/>

<table>
  <tr>
    <td align="center" width="260">
      <img src="docs/screenshots/list.png" width="240" alt="Device list with a scene row on top" /><br/>
      <sub>Scene row + room‑grouped tiles</sub>
    </td>
    <td align="center" width="260">
      <img src="docs/screenshots/detail.png" width="240" alt="Device detail with a vertical slider" /><br/>
      <sub>Vertical slider · picker chips · live readouts</sub>
    </td>
  </tr>
</table>

<sub>Real screenshots on a Galaxy Watch 7 (480×480). Device names anonymized.</sub>

</div>

---

## Why this exists

Controlling Mi Home devices from the wrist usually means dragging out your phone. HomeWrist puts the whole home one wrist‑raise away — favorites, rooms, scenes, and per‑device controls — without a companion server, without watch Wi‑Fi, and **without a per‑model compatibility table**.

## Features

- 🔑 **QR‑code login** — scan with the Mi Home app; the scan itself is your second factor, so captcha / 2FA is bypassed.
- 🏠 **Device list** — favorites pinned to the first screen, everything else grouped by room, sensor readings printed right on the tile.
- ⚡ **Manual scenes** — a chip row at the top of the list; one tap runs "Leaving home" instead of toggling devices one at a time. Also a dedicated **scene Tile**.
- 🎚️ **Detail page** — vertical slider for brightness / temperature (switchable when a device has several continuous values), enums in a picker, actions on tap. **Single‑input actions** (AC target temp, vacuum suction) open a picker and fire.
- ⌚ **Two Tiles + a complication** — a 2×3 device grid, a 2×3 scene grid, and the first favorite's switch right on the watch face.
- 🌐 **Follows the system language** (English / Chinese), spec property labels included.

> **No compatibility table.** Every control is derived at runtime from **MIoT‑Spec‑V2**: fetch the device's spec, reduce properties/actions by category into toggles, sliders, choices, readouts and actions. No `piid` is hardcoded anywhere — new models, firmwares and categories just work.

## Coverage

Measured with `./mi audit` against the public miot‑spec.org corpus (457 models / 177 categories):

| Metric | Result |
|---|---:|
| Usable quick controls derived | **86.9%** |
| Power switch identified | 36.1% |
| Median quick controls per device | 4 |

The remaining 13.1% are mostly devices whose spec exposes no readable/writable member at all (trackers, smart cups — event‑only), plus a few models that surface only vendor‑private services.

Every reduction rule's trade‑off is documented in [`core/.../MiSpec.kt`](core/src/main/kotlin/dev/liji/mihome/core/MiSpec.kt), each tied to a failure measured on the real corpus — e.g. treating the whitelist as a gate blanks out 20% of models; matching the primary service by same‑name category yields zero controls for cameras (their service is called `camera-control`).

## The scene protocol (not in any public source)

The scene endpoints were probed and verified by this project; **every path circulating online is dead**:

```
list  appgateway/miot/appsceneservice/AppSceneService/GetSceneList   { "home_id": <number> }
run   appgateway/miot/appsceneservice/AppSceneService/NewRunScene    { "scene_id": "…", "scene_type": 2, "trigger_key": "user.click" }
```

Three traps, each of which silently breaks an implementation:

1. `RunScene` (without `New`) returns `code:0 result:true` **but the device never moves** — only `NewRunScene` actually executes.
2. `scene_type` is mandatory and must be `2` — unrelated to the scene record's `type` field, which is always `0`.
3. Manual scenes must be filtered by `scene_trigger.triggers[].src == "user"`, or timer/sensor automations leak into the list.

## Architecture

Two modules. `:core` is a pure‑JVM protocol implementation + CLI with **zero Android dependencies**; `:wear` is the Compose for Wear OS UI.

The split isn't for abstraction — it **moves verification from the watch back to the desktop**. A deploy round‑trip is ten‑plus minutes (LAN, watch awake); `./mi list` runs the *exact same code path* as the watch (same `toControls`, same value rendering) and shows in seconds what every device will render as.

```
core/   MiCrypto signing · MiHttp · MiAuth login state machine · MiApi · MiSpec reduction · MiFormat rendering
wear/   AppModel (single state holder) · Ui (Compose) · MiTileService / SceneTileService · MiComplicationService · SpecCache
```

## Building

Needs JDK 21 and an Android SDK (compileSdk 36).

```bash
./gradlew :wear:assembleRelease
adb install -r wear/build/outputs/apk/release/wear-release.apk
adb shell cmd package compile -m speed -f dev.liji.mihome   # instant smoothness (optional)
```

Install the **release** build for daily wear: the `debuggable` flag alone disables enough ART optimization to make fast scrolling drop frames (measured A/B on the watch). A baseline profile is bundled, so the system will AOT‑compile the hot paths eventually — the `compile` line just makes it immediate. Use `assembleDebug` when you need `run-as` log access.

> `settings.gradle.kts` uses Aliyun mirrors because `dl.google.com` is unreachable from mainland China. Outside China, move `google()` to the front.

## CLI

`./mi <cmd>` (the wrapper injects `JAVA_HOME`). Every subcommand runs on the desktop; no watch needed.

```bash
./mi login-qr                  # QR code in the terminal, scan with Mi Home
./mi list                      # exactly what the watch list screen will show
./mi scenes                    # manual scenes (automations excluded)
./mi scene-run <sceneId>       # run a manual scene
```

<details>
<summary>Full command reference</summary>

```bash
./mi devices                   # all devices with their real spec_type
./mi controls-urn <urn>        # what toControls derives for a model
./mi get <did> <siid> <piid>
./mi set <did> <siid> <piid> <value>
./mi action <did> <siid> <aiid> [args…]
./mi audit [samples-per-category]   # coverage check against the full corpus
./mi region [detect|<code>]    # inspect / probe / force account region
./mi raw <path> [json]         # raw endpoint response
```

### Optional: bundling specs and icons

On first launch the watch fetches the spec for every unknown model (3 concurrent, with progress). To skip that, bundle your own home's specs and native Mi Home icons into the APK before building:

```bash
./mi bundle wear/src/main/assets/spec $(./mi devices | grep -o 'urn:[^ ]*' | sort -u)
./mi icons  wear/src/main/assets/icon
```

Both directories are `.gitignore`d — their content depends on your home. Everything still works without them: specs are fetched and cached at runtime, icons fall back to per‑category glyphs. Icons come from `home.miot-spec.com` at build time (`productId` has no official source), so the app never crawls third‑party sites at runtime.

</details>

## Works with your own devices out of the box

The app carries no trace of the author's home:

- **Automatic region detection.** Login is global, but business APIs are per‑region (`de.` / `sg.` / `us.` / `ru.` / `i2.` / `tw.` abroad). A wrong region looks like "login OK but zero devices" — the hardest failure to self‑diagnose, so the user is never asked: each region is probed after login and the one returning a non‑empty home list wins, persisted.
- **All homes.** Multiple properties per account are common; reading only the first would make the rest invisible.
- **Favorites auto‑seeded** with the first 3 switchable devices; your ★ choices take over from there.
- **A fully expired session** goes straight back to the login screen instead of stranding you on a cryptic error.
- **Offline is labeled honestly** — a failed refresh shows "Offline · last known state"; tiles unsynced for 30+ min get a subtle dot. Stale state never impersonates live state.
- **Batched `prop/get`** (80 properties per request) so large homes don't overflow the request body.

## Known limitations

- **Multi‑input actions are out of scope** — single‑input (pick one value) is supported; chaining several inputs isn't worth it on a 33mm round screen.
- No full 2FA flow — QR login *is* the second factor; on a captcha, use QR.
- Some enum values have no official translation (e.g. a speaker's `Stop`); shown as‑is, never invented.
- Scene runs report only "sent" — cloud `code:0` doesn't prove devices moved, and there's nothing to read back, so no fake confirmations.

## Provenance & disclaimer

Login and control use the same endpoints as the official Mi Home app, reconstructed from public materials and traffic captures; no third‑party project code is reused. Xiaomi's `LegalNotice` claims that using its cloud API outside Home Assistant constitutes infringement. This is an independent, non‑official project for personal use and study — not affiliated with or endorsed by Xiaomi. "Mi Home" / "Mijia" / "米家" are trademarks of Xiaomi; they appear here only to describe interoperability. Evaluate the legal situation for yourself before use.

Released under the [MIT License](LICENSE).
