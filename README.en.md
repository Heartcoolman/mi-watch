<div align="center">

<img src="docs/screenshots/icon.png" width="88" alt="Project icon" />

# HomeWrist · 小腕管家

**A third-party Mi Home (Mijia) smart-home client for Wear OS**

[![License](https://img.shields.io/badge/license-MIT-3DA639)](LICENSE)
![Platform](https://img.shields.io/badge/Wear%20OS-6-4285F4?logo=wearos&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-33-3DDC84?logo=android&logoColor=white)

English · [中文](README.md)

</div>

---

## Abstract

This project provides direct control of Mi Home smart-home devices from a Wear OS watch. The watch communicates with Xiaomi's cloud through the paired phone's Bluetooth proxy, and therefore depends on neither an intermediary service such as Home Assistant nor a Wi‑Fi connection on the watch itself. In contrast to conventional implementations that rely on a per‑model compatibility table, this project derives device controls at runtime from the MIoT‑Spec‑V2 specification, thereby supporting arbitrary models, firmware versions, and device categories without code changes. Development and validation were carried out on a Galaxy Watch 7 (Wear OS 6 / Android 16 / 480×480 round display).

<div align="center">
<table>
  <tr>
    <td align="center" width="260">
      <img src="docs/screenshots/list.png" width="230" alt="Device list screen" /><br/>
      <sub><b>Fig. 1</b> &nbsp; Device list: scene row on top, room‑grouped tiles</sub>
    </td>
    <td align="center" width="260">
      <img src="docs/screenshots/detail.png" width="230" alt="Device detail screen" /><br/>
      <sub><b>Fig. 2</b> &nbsp; Device detail: vertical slider, enum picker, live readouts</sub>
    </td>
  </tr>
</table>
<sub>Screenshots on a Galaxy Watch 7 (480×480); device names anonymized.</sub>
</div>

## Features

- **QR-code authentication.** Login is completed by scanning, with the Mi Home app, a QR code shown on the watch. The scan itself constitutes a second authentication factor, so no captcha or secondary‑verification flow is required.
- **Device list.** Favorites are pinned to the first screen; the remainder are grouped by room. Sensor readings are rendered directly on the tile.
- **Manual scenes.** Manual scenes are presented as a horizontal row at the top of the list and triggered with a single tap; a dedicated scene Tile is also provided.
- **Device detail.** A vertical slider adjusts continuous quantities such as brightness and temperature (selectable when several are present); enumerated properties are chosen through an overlay; input‑free actions fire on tap. For **single‑input actions** (e.g. air‑conditioner target temperature, vacuum suction level), a value is chosen in an overlay and dispatched directly.
- **Watch-face surfaces.** Two Tiles (a 2×3 device‑switch grid and a 2×3 scene grid) and one watch‑face complication (the first favorite's switch state) are provided.
- **Localization.** The interface language follows the system setting (Chinese / English), as do device property labels.

## Control derivation

Every interactive control is derived at runtime from the [MIoT‑Spec‑V2](https://iot.mi.com/new/doc/design/spec/overview) specification, with no hardcoded property identifiers (`piid`). The procedure is as follows: the device specification is fetched, then its properties and actions are reduced, by category, into five control kinds — toggles, sliders, choices, read‑outs, and actions. Because the reduction depends on category rather than specific identifiers, changing model, firmware, or category requires no code change.

The rationale and trade‑off behind each reduction rule are documented in the comments of [`core/.../MiSpec.kt`](core/src/main/kotlin/dev/liji/mihome/core/MiSpec.kt); each rule corresponds to a failure observed on the real corpus. For instance, using the property whitelist as an admission gate renders roughly 20% of models as empty cards, and matching the primary service by same‑name category alone yields no controls for cameras (whose service is named `camera-control`).

## Coverage evaluation

Measured with `./mi audit` over a sample of the public miot‑spec.org corpus (457 models / 177 categories):

| Metric | Result |
|---|---:|
| Models with usable quick controls derived | **86.9%** |
| Models with a power switch identified | 36.1% |
| Median quick controls per model | 4 |

The remaining 13.1% are largely devices whose specification exposes no readable or writable member at all (e.g. trackers, smart cups — event‑only models), together with a few models that expose only vendor‑private services.

## The scene protocol

The cloud endpoints for Mi Home scenes are undocumented; their addresses were determined empirically by this project. Several endpoint paths circulating online are defunct. The working interface is:

```
list    appgateway/miot/appsceneservice/AppSceneService/GetSceneList
        { "home_id": <integer> }

run     appgateway/miot/appsceneservice/AppSceneService/NewRunScene
        { "scene_id": "…", "scene_type": 2, "trigger_key": "user.click" }
```

Three points, each of which silently breaks an implementation, were confirmed by testing:

1. `RunScene` (without the `New` prefix) under the same service returns `code:0` and `result:true`, yet the device performs no action; only `NewRunScene` takes effect.
2. `scene_type` is mandatory and must equal `2`. It is unrelated to the scene record's `type` field, which is always `0`.
3. Manual scenes must be filtered by `scene_trigger.triggers[].src == "user"`; otherwise timer‑ and sensor‑triggered automations enter the list.

## System architecture

The project comprises two modules: `:core`, a pure‑JVM protocol implementation and command‑line tool with no Android dependencies, and `:wear`, the interface layer built on Compose for Wear OS.

The purpose of this division is to **move verification from the watch back to the desktop**. A full device‑side deployment traverses the local network and awaits the watch waking, taking upward of ten minutes; the command‑line tool `./mi list` exercises exactly the same code path as the watch (the same `toControls` reduction and value rendering) and produces each device's rendered result within seconds.

```
core/   MiCrypto signing · MiHttp · MiAuth login state machine · MiApi · MiSpec reduction · MiFormat rendering
wear/   AppModel state holder · Ui · MiTileService / SceneTileService · MiComplicationService · SpecCache
```

## Build

Requirements: JDK 21 and an Android SDK (compileSdk 36).

```bash
./gradlew :wear:assembleRelease
adb install -r wear/build/outputs/apk/release/wear-release.apk
adb shell cmd package compile -m speed -f dev.liji.mihome   # optional: complete AOT compilation immediately
```

The **release** build is recommended for daily use. The `debuggable` flag alone disables a substantial portion of ART optimization, causing dropped frames during fast scrolling (confirmed by on‑device A/B comparison). A baseline profile is bundled, so the system will eventually AOT‑compile the hot paths on its own; the `compile` command above merely makes this effect immediate. Use `assembleDebug` when `run-as` log access is required.

> This repository's `settings.gradle.kts` uses Aliyun mirrors, as `dl.google.com` is not directly reachable from mainland China. Users elsewhere may place `google()` first in the source list.

## Command-line tool

`./mi <command>` (the wrapper injects `JAVA_HOME`). All subcommands run on the desktop; no watch connection is required.

```bash
./mi login-qr                  # render a QR code in the terminal for the Mi Home app to scan
./mi list                      # output identical to the watch list screen
./mi scenes                    # list manual scenes (automations excluded)
./mi scene-run <sceneId>       # run a given manual scene
```

<details>
<summary>Full command reference</summary>

```bash
./mi devices                   # list all devices with their real spec_type
./mi controls-urn <urn>        # controls that toControls derives for a given model
./mi get <did> <siid> <piid>
./mi set <did> <siid> <piid> <value>
./mi action <did> <siid> <aiid> [args…]
./mi audit [samples-per-category]   # evaluate reduction coverage over the full corpus
./mi region [detect|<code>]    # inspect / probe / set the account region
./mi raw <path> [json]         # raw endpoint response
```

### Optional: bundling specifications and icons

On first launch the watch fetches the specification for each unknown model (concurrency 3, with a progress indicator). To omit this step, bundle your own devices' specifications and native Mi Home icons into the APK before building:

```bash
./mi bundle wear/src/main/assets/spec $(./mi devices | grep -o 'urn:[^ ]*' | sort -u)
./mi icons  wear/src/main/assets/icon
```

Both directories are listed in `.gitignore`, as their contents depend on the user's own devices. The application still functions without them: specifications are fetched and cached at runtime, and icons fall back to per‑category rendered glyphs. Icons are obtained from `home.miot-spec.com` at build time (`productId` has no official source), so the application accesses no third‑party site at runtime.

## Operating with any account

The application contains no special handling tied to the author's environment:

- **Automatic region detection.** Login is global, but business APIs are partitioned by region (foreign prefixes `de.` / `sg.` / `us.` / `ru.` / `i2.` / `tw.`). A wrong region manifests as "login succeeds but no devices appear" — among the hardest failures to self‑diagnose — and is therefore not left to the user: after login, each region is probed and the first returning a non‑empty home list is adopted and persisted.
- **All homes.** A single account bound to multiple residences is common; reading only the first home would render the remaining devices entirely invisible.
- **Favorite initialization.** The first three switchable devices are favorited on first launch; the user's subsequent choices in the detail page take precedence thereafter.
- **Session expiry.** On complete session expiry, the application returns directly to the login screen rather than remaining on an unrecoverable error.
- **Explicit offline state.** On a failed refresh, "Offline · last known state" is shown atop the list; a tile unsynchronized for more than 30 minutes receives a faint marker. Stale state never masquerades as live state.
- **Batched reads.** `prop/get` carries at most 80 properties per batch, so homes with many devices do not exceed the request‑body limit.

## Limitations

- **Multi‑input actions are unsupported.** Single‑input actions (choosing one value in an overlay) are supported; entering several inputs in sequence on a 33mm round display is too costly and is not implemented.
- **No full two‑factor flow.** QR login is itself the second factor; on a captcha, use QR login.
- **Some enumerated values have no official translation** (e.g. a speaker's `Stop` playback state) and are shown verbatim rather than speculatively translated.
- **Scene execution reports only "sent."** A cloud `code:0` does not establish that a device acted, and there is nothing to read back, so no false confirmation is issued.

## Provenance and compliance

Login and control use the same endpoints as the official Mi Home app, reconstructed from public materials and traffic captures; no third‑party project code is reused. Xiaomi's `LegalNotice` asserts that using its cloud API outside Home Assistant constitutes infringement. This is an independent, non‑official implementation intended for personal use and study, with no affiliation with or endorsement by Xiaomi. "Mi Home," "Mijia," and "米家" are trademarks of Xiaomi and appear here solely to describe interoperability. Assess the applicable legal risk before use.

Released under the [MIT License](LICENSE).
