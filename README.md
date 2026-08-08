# mi-watch

English | [中文](README.zh.md)

A third-party Mi Home (Mijia) client for Wear OS. The watch talks to Xiaomi's cloud **directly** — network goes through the paired phone's Bluetooth proxy, so it needs neither Home Assistant nor watch Wi-Fi.

Developed and verified on a Galaxy Watch 7 (Wear OS 6 / Android 16 / 480×480).

## What it does

- QR-code login (scan with the Mi Home app — naturally bypasses captcha / 2FA)
- Device list: favorites pinned on the first screen, the rest grouped by room
- **Manual scenes**: a chip row at the top of the list — one tap runs "Leaving home" instead of toggling devices one by one
- Detail page: vertical slider for brightness/temperature (switchable when a device has several continuous values), enum properties in a picker, actions fire on tap; **single-input actions** (AC target temperature, vacuum suction) open a picker and fire
- Two tiles: a 2×3 device-switch grid and a 2×3 scene grid — raise wrist, tap, light on
- Watch-face complication: first favorite's power state, toggle right on the watch face
- Sensor readings shown directly on list tiles
- UI language follows the system (English/Chinese); spec property labels follow it too

**Device support does not rely on a compatibility table.** Every control is derived at runtime from MIoT-Spec-V2: fetch the device's spec, reduce properties/actions by category into toggles, sliders, choices, readouts and actions. No piid is hardcoded anywhere, so new models, firmwares and categories work without code changes.

## Coverage

Measured with `./mi audit` against the public miot-spec.org corpus (457 models / 177 categories):

| Metric | Result |
|---|---|
| Usable quick controls derived | 86.9% |
| Power switch identified | 36.1% |
| Median quick controls per device | 4 |

The remaining 13.1% are mostly devices whose spec has no readable/writable member at all (trackers, smart cups — event-only), plus a few models that expose only vendor-private services.

Every reduction rule's trade-off is documented in `core/.../MiSpec.kt`, each tied to a failure measured on the real corpus — e.g. treating the whitelist as a gate blanks out 20% of models; matching the primary service by same-name category yields zero controls for cameras (their service is called `camera-control`).

## The scene protocol (not found in public sources)

The scene endpoints were probed and verified by this project; every path circulating online is dead:

- List: `appgateway/miot/appsceneservice/AppSceneService/GetSceneList` with `{"home_id": <number>}`
- Run: `.../AppSceneService/NewRunScene` with `{"scene_id":"…","scene_type":2,"trigger_key":"user.click"}`

Three traps: `RunScene` (without `New`) returns success **but the device never moves**; `scene_type` is mandatory and must be `2` (unrelated to the scene record's `type` field, which is always 0); manual scenes must be filtered by `scene_trigger.triggers[].src == "user"`, otherwise timer/sensor automations leak in.

## Architecture

Two modules. `:core` is a pure-JVM protocol implementation + CLI with **zero Android dependencies**; `:wear` is the Compose for Wear OS UI.

The split is not for abstraction — it moves **verification from the watch back to the desktop**. A deploy round-trip takes ten-plus minutes (LAN, watch awake); `./mi list` runs the exact same code path as the watch (same `toControls`, same value rendering) and shows in seconds what every device will look like.

```
core/   MiCrypto signing · MiHttp · MiAuth login state machine · MiApi · MiSpec reduction · MiFormat rendering
wear/   AppModel single state holder · Ui (Compose) · MiTileService / SceneTileService · MiComplicationService · SpecCache
```

## Building

Needs JDK 21 and an Android SDK (compileSdk 36).

```bash
./gradlew :wear:assembleRelease    # or your own gradle
adb install -r wear/build/outputs/apk/release/wear-release.apk
adb shell cmd package compile -m speed -f dev.liji.mihome   # instant smoothness (optional)
```

Install the **release** build for daily use: the `debuggable` flag alone disables enough ART
optimization to make fast scrolling drop frames (measured A/B on the watch). A baseline profile
is bundled, so the system will AOT-compile the hot paths on its own eventually — the `compile`
line above just makes it immediate. Use `assembleDebug` when you need `run-as` log access.

> `settings.gradle.kts` uses Aliyun mirrors because `dl.google.com` is unreachable from mainland China. Outside China, move `google()` to the front.

## CLI

`./mi <cmd>` (the wrapper injects JAVA_HOME). Every subcommand runs on the desktop; no watch needed.

```bash
./mi login-qr                  # QR code in the terminal, scan with Mi Home
./mi devices                   # all devices with their real spec_type
./mi list                      # exactly what the watch list screen will show
./mi scenes                    # manual scenes (automations excluded)
./mi scene-run <sceneId>       # run a manual scene
./mi controls-urn <urn>        # what toControls derives for a model
./mi get <did> <siid> <piid>
./mi set <did> <siid> <piid> <value>
./mi action <did> <siid> <aiid> [args…]
./mi audit [samples-per-category]   # coverage check against the full corpus
./mi raw <path> [json]         # raw endpoint response
```

### Optional: bundling specs and icons

On first launch the watch fetches the spec for every unknown model (3 concurrent, with progress). To skip that, bundle your own home's specs and native Mi Home icons into the APK before building:

```bash
./mi bundle wear/src/main/assets/spec $(./mi devices | grep -o 'urn:[^ ]*' | sort -u)
./mi icons  wear/src/main/assets/icon
```

Both directories are in `.gitignore` — their content depends on your home and doesn't belong in the repo. Everything still works without them: specs are fetched and cached at runtime, icons fall back to per-category glyphs.

Icons live at `cnbj1.fds.api.xiaomi.com/iotweb-product-center/<productId>.png`, but **productId has no official source** (`home_device_list` doesn't return it, `pid` is always 0); it is currently parsed from the community site `home.miot-spec.com`. That's why this step happens at build time — the app never crawls third-party sites at runtime.

## Works with your own devices out of the box

The app carries no trace of the author's home:

- **Automatic region detection.** Login is global, but business APIs are per-region (`de.` / `sg.` / `us.` / `ru.` / `i2.` / `tw.` prefixes abroad). The wrong region looks like "login OK but zero devices" — the hardest failure to self-diagnose, so the user is never asked: after login each region is probed and the one returning a non-empty home list wins, persisted. On the desktop, `./mi region` / `./mi region detect`.
- **All homes.** Multiple properties per account are common; reading only the first would make the rest invisible.
- **Favorites auto-seeded** with the first 3 switchable devices; your ★ choices take over from there.
- **Specs fetched at runtime** and cached forever; unknown models just work.
- **A fully expired session goes straight back to the login screen** instead of stranding you on a cryptic error.
- **Offline is labeled honestly**: a failed refresh shows "Offline · last known state" atop the list; tiles not synced for 30+ minutes get a subtle dot — stale state never impersonates live state.
- **Batched prop/get** (80 properties per request) so large homes don't overflow the request body.

## Known limitations

- **Multi-input actions are out of scope** — single-input (pick one value) is supported; chaining several inputs isn't worth it on a 33mm round screen.
- No full 2FA flow. QR login is itself a second factor; if you hit a captcha, use QR.
- Some enum values have no official Chinese translation (e.g. a speaker's `Stop`); shown as-is, never invented.
- Scene runs report only "sent": cloud `code=0` doesn't prove the devices moved, and there's nothing to read back — so no fake confirmations.

## Protocol provenance & disclaimer

Login and control use the same endpoints as the official Mi Home app, reconstructed from public materials and traffic captures; no third-party project code is reused. Xiaomi's `LegalNotice` claims that using its cloud API outside Home Assistant constitutes infringement; this project is for personal use and study — evaluate for yourself.

MIT License.
