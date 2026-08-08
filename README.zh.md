# mi-watch

[English](README.md) | 中文

Wear OS 上的第三方米家客户端。手表**直连小米云端**控制设备，网络由配对手机通过蓝牙代理，不依赖 Home Assistant，也不需要手表联网 Wi-Fi。

在 Galaxy Watch 7（Wear OS 6 / Android 16 / 480×480）上开发和验证。

## 它能做什么

- 扫码登录（用米家 App 扫手表上的二维码，天然绕过验证码/二次验证）
- 设备列表：收藏置顶一屏直达，其余按房间分组
- **手动场景**：列表页顶部一排，一点即发；「离家」一下换一串设备各就各位
- 详情页：竖滑块调亮度/温度（多个连续量可切换），枚举属性弹层选择，动作一点即发；空调设温、扫地机吸力这类**单入参动作**弹层选档后直发
- 两个 Tile：设备开关 2×3 网格、场景 2×3 网格——抬腕、一点、灯亮
- 表盘 Complication：第一个收藏设备的开关状态，表盘上直接点
- 传感器读数直接显示在列表页
- 界面语言跟随系统（中/英）；spec 属性名同样跟随系统语言

**设备支持不靠适配表。** 所有控件都由 MIoT-Spec-V2 运行时推导：拉到设备的 spec，按属性/动作的类别归约成开关、滑块、枚举、只读量、动作。没有一处硬编码 piid，所以换型号、换固件、换品类都不用改代码。

## 覆盖率

用 `./mi audit` 对 miot-spec.org 的公开语料做过抽样测量（457 个型号 / 177 个品类）：

| 指标 | 结果 |
|---|---|
| 能推导出常用控件 | 86.9% |
| 识别出电源开关 | 36.1% |
| 常用控件数中位数 | 4 |

剩下 13.1% 主要是 spec 里根本没有可读写成员的设备（tracker、cup 这类只上报事件的），以及少数只暴露厂商私有服务的型号。

规则的取舍都记在 `core/.../MiSpec.kt` 的注释里，每条都对应一个用真实语料测出来的失败——比如「白名单当门禁」会让 20% 的型号变成空卡片，「主服务按类别同名查找」会让摄像机（服务叫 `camera-control`）零控件。

## 场景协议（公开资料里没有的部分）

场景端点是这个项目实测探出来的，网上流传的路径全部失效：

- 列表：`appgateway/miot/appsceneservice/AppSceneService/GetSceneList`，参数 `{"home_id": <数字>}`
- 执行：`.../AppSceneService/NewRunScene`，参数 `{"scene_id":"…","scene_type":2,"trigger_key":"user.click"}`

三个坑：`RunScene`（不带 New）返回成功但设备不动；`scene_type` 必填且为 2（与场景记录里恒为 0 的 `type` 字段无关）；手动场景要按 `scene_trigger.triggers[].src == "user"` 过滤，否则自动化会混进来。

## 架构

两个模块。`:core` 是**零 Android 依赖**的纯 JVM 协议实现 + CLI，`:wear` 是 Compose for Wear OS 界面。

这样拆不是为了抽象，是为了**把验证从手表挪回桌面**。刷一次机要经局域网、等手表醒着，一轮十几分钟；而 `./mi list` 走的是和表上完全相同的代码路径（同一份 `toControls`、同一份值渲染），秒级就能看出每台设备会显示成什么。

```
core/   MiCrypto 签名 · MiHttp · MiAuth 登录状态机 · MiApi · MiSpec 归约 · MiFormat 渲染
wear/   AppModel 单一状态持有者 · Ui Compose 界面 · MiTileService / SceneTileService · MiComplicationService · SpecCache
```

## 构建

需要 JDK 21 和 Android SDK（compileSdk 36）。

```bash
./gradlew :wear:assembleRelease    # 或用你自己的 gradle
adb install -r wear/build/outputs/apk/release/wear-release.apk
adb shell cmd package compile -m speed -f dev.liji.mihome   # 立刻顺滑（可选）
```

日常佩戴装 **release** 包：仅 `debuggable` 标志本身就会禁用足量的 ART 优化，
让快速滑动掉帧（真机 A/B 实测）。包里带了 baseline profile，系统迟早会自己把热路径
AOT 掉——上面那行 `compile` 只是让它立刻生效。需要 `run-as` 取日志时再用 debug 包。

> 本仓库的 `settings.gradle.kts` 用阿里云镜像。国内直连 `dl.google.com` 不通，境外用户可以把 `google()` 提到前面。

## CLI

`./mi <cmd>`（包装脚本会注入 JAVA_HOME）。所有子命令都在桌面跑，不需要手表。

```bash
./mi login-qr                  # 终端画二维码，用米家 App 扫
./mi devices                   # 列出全部设备和真实 spec_type
./mi list                      # 表上列表页会显示什么，一模一样
./mi scenes                    # 手动场景列表（自动化不列）
./mi scene-run <sceneId>       # 执行一个手动场景
./mi controls-urn <urn>        # 看 toControls 对某个型号推出了什么
./mi get <did> <siid> <piid>
./mi set <did> <siid> <piid> <value>
./mi action <did> <siid> <aiid> [args…]
./mi audit [每品类抽样数]        # 拿全量语料检验归约规则的覆盖率
./mi raw <path> [json]         # 原样打印端点响应
```

### 可选：预置 spec 和图标

首次启动时，手表要为每个未知型号拉 spec（并发 3，带进度）。想省掉这一步，可以在构建前把自己家设备的 spec 和米家原生图标打进 APK：

```bash
./mi bundle wear/src/main/assets/spec $(./mi devices | grep -o 'urn:[^ ]*' | sort -u)
./mi icons  wear/src/main/assets/icon
```

这两个目录已在 `.gitignore` 里——内容取决于你家有哪些设备，不该进版本库。缺了也能跑：spec 会运行时抓取并缓存，图标会退回按品类自绘的图形。

图标地址是 `cnbj1.fds.api.xiaomi.com/iotweb-product-center/<productId>.png`，但 **productId 没有官方来源**（`home_device_list` 不返回，`pid` 恒为 0），目前只能从社区站 `home.miot-spec.com` 的产品页解析。所以这一步放在构建期，App 运行时不会去爬任何第三方站点。

## 装上就能用你自己的设备

这个 App 不带任何「我家」的痕迹，装上后对着你自己的账号工作：

- **区域自动探测**。登录流程全球统一，但业务接口按区域分开（海外是 `de.` / `sg.` / `us.` / `ru.` / `i2.` / `tw.` 前缀）。选错的表现是「登录成功但一个设备都没有」——这是最难自查的一类问题，所以不让用户选：登录后逐个试，谁能返回非空家庭列表就是谁，结果落盘。桌面上可以用 `./mi region` / `./mi region detect` 查看和重跑。
- **全部家庭**。多套房产是常态（作者账号就有三个家庭），只读第一个会让其余的设备完全不存在。
- **收藏自动初始化**为前 3 个可开关的设备，之后以你在详情页 ★ 的选择为准。
- **spec 运行时抓取**并永久缓存，未知型号也能用；首启并发 3 拉取并显示进度。
- **会话彻底过期直接回登录页**，不会留你对着一条看不懂的报错反复点刷新。
- **离线如实标注**：刷新失败时列表顶部提示「离线 · 显示上次状态」，Tile 超过 30 分钟没同步会在格子里加一个淡点——旧状态不冒充实时。
- **prop/get 分批**（每批 80 个属性），设备多的家庭不会撑爆请求体。

## 已知限制

- **多入参动作不做**——单入参（弹层选档）已支持；连填多个值的链路在 33mm 圆屏上不值得。
- 完整的 2FA 链路不做。扫码登录本身就是第二因子，遇到验证码请改用扫码。
- 部分枚举值小米自己没有中文翻译（比如音箱播放状态的 `Stop`），照原文显示，不编造。
- 场景执行只报「已下发」：云端 `code=0` 不等于设备真的动了，没有可回读的凭据就不冒充有。

## 协议来源与免责

登录与控制走的是米家 App 自己用的接口，由公开资料和抓包整理而成，没有复用任何第三方项目的代码。小米的 `LegalNotice` 声称在非 Home Assistant 平台使用其云接口构成侵权；本项目为个人自用与学习目的，请自行评估。

MIT License。
