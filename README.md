<div align="center">

<img src="docs/screenshots/icon.png" width="96" alt="小腕管家图标" />

# 小腕管家 &nbsp;<sub>HomeWrist</sub>

**Wear OS 上的第三方米家客户端。**
手表**直连小米云端**控制设备，网络由配对手机通过蓝牙代理，不依赖 Home Assistant，也不需要手表联网 Wi‑Fi。

[![License](https://img.shields.io/badge/license-MIT-3DA639)](LICENSE)
![Platform](https://img.shields.io/badge/Wear%20OS-6-4285F4?logo=wearos&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-33-3DDC84?logo=android&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-for%20Wear%20OS-4285F4)

[English](README.en.md) · 中文

<br/>

<table>
  <tr>
    <td align="center" width="260">
      <img src="docs/screenshots/list.png" width="240" alt="顶部场景行的设备列表" /><br/>
      <sub>场景行 + 按房间分组的磁贴</sub>
    </td>
    <td align="center" width="260">
      <img src="docs/screenshots/detail.png" width="240" alt="竖滑块详情页" /><br/>
      <sub>竖滑块 · 弹层 chip · 实时读数</sub>
    </td>
  </tr>
</table>

<sub>Galaxy Watch 7（480×480）真机截图，设备名已脱敏。</sub>

</div>

---

## 为什么做它

在手表上控米家设备，通常意味着还得掏出手机。小腕管家把整个家搬到抬腕即达——收藏、房间、场景、逐设备控制——不用伴侣服务器，不用手表 Wi‑Fi，**也不靠一张按型号的适配表**。

## 功能

- 🔑 **扫码登录** —— 用米家 App 扫手表上的二维码，扫码本身就是第二因子，天然绕过验证码 / 二次验证。
- 🏠 **设备列表** —— 收藏置顶一屏直达，其余按房间分组，传感器读数直接印在磁贴上。
- ⚡ **手动场景** —— 列表页顶部一排 chip，一点即发；「离家」一下换一串设备各就各位。另有独立**场景 Tile**。
- 🎚️ **详情页** —— 竖滑块调亮度 / 温度（多个连续量可切换），枚举弹层选择，动作一点即发。空调设温、扫地机吸力这类**单入参动作**弹层选档后直发。
- ⌚ **两个 Tile + 表盘 Complication** —— 设备开关 2×3 网格、场景 2×3 网格，以及第一个收藏设备的开关直接在表盘上。
- 🌐 **界面跟随系统语言**（中 / 英），spec 属性名同样跟随。

> **不靠适配表。** 所有控件都由 **MIoT‑Spec‑V2** 运行时推导：拉到设备的 spec，按属性 / 动作的类别归约成开关、滑块、枚举、只读量、动作。没有一处硬编码 `piid`——换型号、换固件、换品类都不用改代码。

## 覆盖率

用 `./mi audit` 对 miot‑spec.org 的公开语料做过抽样测量（457 个型号 / 177 个品类）：

| 指标 | 结果 |
|---|---:|
| 能推导出常用控件 | **86.9%** |
| 识别出电源开关 | 36.1% |
| 常用控件数中位数 | 4 |

剩下 13.1% 主要是 spec 里根本没有可读写成员的设备（tracker、cup 这类只上报事件的），以及少数只暴露厂商私有服务的型号。

规则的取舍都记在 [`core/.../MiSpec.kt`](core/src/main/kotlin/dev/liji/mihome/core/MiSpec.kt) 的注释里，每条都对应一个用真实语料测出来的失败——比如「白名单当门禁」会让 20% 的型号变成空卡片，「主服务按类别同名查找」会让摄像机（服务叫 `camera-control`）零控件。

## 场景协议（公开资料里没有的部分）

场景端点是这个项目实测探出来的，**网上流传的路径全部失效**：

```
列表  appgateway/miot/appsceneservice/AppSceneService/GetSceneList   { "home_id": <数字> }
执行  appgateway/miot/appsceneservice/AppSceneService/NewRunScene    { "scene_id": "…", "scene_type": 2, "trigger_key": "user.click" }
```

三个坑，每一个都会让实现悄悄失效：

1. `RunScene`（不带 `New`）返回 `code:0 result:true`，**但设备纹丝不动**——只有 `NewRunScene` 真执行。
2. `scene_type` 必填且为 `2`——与场景记录里恒为 `0` 的 `type` 字段无关。
3. 手动场景要按 `scene_trigger.triggers[].src == "user"` 过滤，否则定时 / 传感器自动化会混进来。

## 架构

两个模块。`:core` 是**零 Android 依赖**的纯 JVM 协议实现 + CLI，`:wear` 是 Compose for Wear OS 界面。

这样拆不是为了抽象，而是**把验证从手表挪回桌面**。刷一次机要经局域网、等手表醒着，一轮十几分钟；而 `./mi list` 走的是和表上*完全相同*的代码路径（同一份 `toControls`、同一份值渲染），秒级就能看出每台设备会显示成什么。

```
core/   MiCrypto 签名 · MiHttp · MiAuth 登录状态机 · MiApi · MiSpec 归约 · MiFormat 渲染
wear/   AppModel 单一状态持有者 · Ui Compose 界面 · MiTileService / SceneTileService · MiComplicationService · SpecCache
```

## 构建

需要 JDK 21 和 Android SDK（compileSdk 36）。

```bash
./gradlew :wear:assembleRelease
adb install -r wear/build/outputs/apk/release/wear-release.apk
adb shell cmd package compile -m speed -f dev.liji.mihome   # 立刻顺滑（可选）
```

日常佩戴装 **release** 包：仅 `debuggable` 标志本身就会禁用足量的 ART 优化，让快速滑动掉帧（真机 A/B 实测）。包里带了 baseline profile，系统迟早会自己把热路径 AOT 掉——上面那行 `compile` 只是让它立刻生效。需要 `run-as` 取日志时再用 `assembleDebug`。

> 本仓库的 `settings.gradle.kts` 用阿里云镜像。国内直连 `dl.google.com` 不通，境外用户可以把 `google()` 提到前面。

## CLI

`./mi <cmd>`（包装脚本会注入 `JAVA_HOME`）。所有子命令都在桌面跑，不需要手表。

```bash
./mi login-qr                  # 终端画二维码，用米家 App 扫
./mi list                      # 表上列表页会显示什么，一模一样
./mi scenes                    # 手动场景列表（自动化不列）
./mi scene-run <sceneId>       # 执行一个手动场景
```

<details>
<summary>完整命令参考</summary>

```bash
./mi devices                   # 列出全部设备和真实 spec_type
./mi controls-urn <urn>        # 看 toControls 对某个型号推出了什么
./mi get <did> <siid> <piid>
./mi set <did> <siid> <piid> <value>
./mi action <did> <siid> <aiid> [args…]
./mi audit [每品类抽样数]        # 拿全量语料检验归约规则的覆盖率
./mi region [detect|<code>]    # 查看 / 探测 / 强设账号区域
./mi raw <path> [json]         # 原样打印端点响应
```

### 可选：预置 spec 和图标

首次启动时，手表要为每个未知型号拉 spec（并发 3，带进度）。想省掉这一步，可以在构建前把自己家设备的 spec 和米家原生图标打进 APK：

```bash
./mi bundle wear/src/main/assets/spec $(./mi devices | grep -o 'urn:[^ ]*' | sort -u)
./mi icons  wear/src/main/assets/icon
```

这两个目录已在 `.gitignore` 里——内容取决于你家有哪些设备。缺了也能跑：spec 会运行时抓取并缓存，图标会退回按品类自绘的图形。图标在构建期从 `home.miot-spec.com` 抓（`productId` 没有官方来源），所以 App 运行时不会去爬任何第三方站点。

</details>

## 装上就能用你自己的设备

这个 App 不带任何「我家」的痕迹：

- **区域自动探测。** 登录流程全球统一，但业务接口按区域分开（海外是 `de.` / `sg.` / `us.` / `ru.` / `i2.` / `tw.` 前缀）。选错的表现是「登录成功但一个设备都没有」——最难自查的一类问题，所以不让用户选：登录后逐个试，谁能返回非空家庭列表就是谁，结果落盘。
- **全部家庭。** 多套房产是常态，只读第一个会让其余的设备完全不存在。
- **收藏自动初始化**为前 3 个可开关的设备，之后以你在详情页 ★ 的选择为准。
- **会话彻底过期**直接回登录页，不会留你对着一条看不懂的报错反复点刷新。
- **离线如实标注**——刷新失败时列表顶部提示「离线 · 显示上次状态」，Tile 超过 30 分钟没同步会在格子里加一个淡点。旧状态不冒充实时。
- **prop/get 分批**（每批 80 个属性），设备多的家庭不会撑爆请求体。

## 已知限制

- **多入参动作不做**——单入参（弹层选档）已支持；连填多个值的链路在 33mm 圆屏上不值得。
- 完整的 2FA 链路不做——扫码登录本身*就是*第二因子，遇到验证码请改用扫码。
- 部分枚举值小米自己没有官方翻译（比如音箱播放状态的 `Stop`），照原文显示，不编造。
- 场景执行只报「已下发」——云端 `code:0` 不等于设备真的动了，没有可回读的凭据就不冒充有。

## 协议来源与免责

登录与控制走的是米家 App 自己用的接口，由公开资料和抓包整理而成，没有复用任何第三方项目的代码。小米的 `LegalNotice` 声称在非 Home Assistant 平台使用其云接口构成侵权。本项目是独立的非官方项目，仅供个人自用与学习，与小米无任何关联、未获其认可。「Mi Home」/「Mijia」/「米家」是小米的商标，这里出现仅为说明互操作性。请自行评估法律风险后再使用。

以 [MIT License](LICENSE) 发布。
