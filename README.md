# mi-watch

Wear OS 上的第三方米家客户端。手表**直连小米云端**控制设备，网络由配对手机通过蓝牙代理，不依赖 Home Assistant，也不需要手表联网 Wi-Fi。

在 Galaxy Watch 7（Wear OS 6 / Android 16 / 480×480）上开发和验证。

## 它能做什么

- 扫码登录（用米家 App 扫手表上的二维码，天然绕过验证码/二次验证）
- 设备列表：收藏置顶一屏直达，其余按房间分组
- 详情页：竖滑块调亮度/温度，枚举属性弹层选择，无入参动作一点即发
- Tile：抬腕、一点、灯亮
- 传感器读数直接显示在列表页

**设备支持不靠适配表。** 所有控件都由 MIoT-Spec-V2 运行时推导：拉到设备的 spec，按属性/动作的类别归约成开关、滑块、枚举、只读量、动作。没有一处硬编码 piid，所以换型号、换固件、换品类都不用改代码。

## 覆盖率

用 `./mi audit` 对 miot-spec.org 的公开语料做过抽样测量（457 个型号 / 177 个品类）：

| 指标 | 结果 |
|---|---|
| 能推导出常用控件 | 86.4% |
| 识别出电源开关 | 36.1% |
| 常用控件数中位数 | 4 |

剩下 13.6% 主要是 spec 里根本没有可读写成员的设备（tracker、cup 这类只上报事件的），以及少数只暴露厂商私有服务的型号。

规则的取舍都记在 `core/.../MiSpec.kt` 的注释里，每条都对应一个用真实语料测出来的失败——比如「白名单当门禁」会让 20% 的型号变成空卡片，「主服务按类别同名查找」会让摄像机（服务叫 `camera-control`）零控件。

## 架构

两个模块。`:core` 是**零 Android 依赖**的纯 JVM 协议实现 + CLI，`:wear` 是 Compose for Wear OS 界面。

这样拆不是为了抽象，是为了**把验证从手表挪回桌面**。刷一次机要经局域网、等手表醒着，一轮十几分钟；而 `./mi list` 走的是和表上完全相同的代码路径（同一份 `toControls`、同一份值渲染），秒级就能看出每台设备会显示成什么。

```
core/   MiCrypto 签名 · MiHttp · MiAuth 登录状态机 · MiApi · MiSpec 归约 · MiFormat 渲染
wear/   AppModel 单一状态持有者 · Ui Compose 界面 · MiTileService · SpecCache
```

## 构建

需要 JDK 21 和 Android SDK（compileSdk 36）。

```bash
./gradlew :wear:assembleDebug      # 或用你自己的 gradle
adb install -r -t wear/build/outputs/apk/debug/wear-debug.apk
```

> 本仓库的 `settings.gradle.kts` 用阿里云镜像。国内直连 `dl.google.com` 不通，境外用户可以把 `google()` 提到前面。

## CLI

`./mi <cmd>`（包装脚本会注入 JAVA_HOME）。所有子命令都在桌面跑，不需要手表。

```bash
./mi login-qr                  # 终端画二维码，用米家 App 扫
./mi devices                   # 列出全部设备和真实 spec_type
./mi list                      # 表上列表页会显示什么，一模一样
./mi controls-urn <urn>        # 看 toControls 对某个型号推出了什么
./mi get <did> <siid> <piid>
./mi set <did> <siid> <piid> <value>
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

## 已知限制

- **仅国服**。基址写死 `api.io.mi.com`，海外账号需要区域前缀（`de.` / `sg.` / `us.` / `i2.`），尚未实现。
- **多家庭**取第一个家庭，没有切换入口。
- 带入参的动作不做——需要先让用户填值，33mm 圆屏上不值得。
- 完整的 2FA 链路不做。扫码登录本身就是第二因子，遇到验证码请改用扫码。
- 部分枚举值小米自己没有中文翻译（比如音箱播放状态的 `Stop`），照原文显示，不编造。

## 协议来源与免责

登录与控制走的是米家 App 自己用的接口，由公开资料和抓包整理而成，没有复用任何第三方项目的代码。小米的 `LegalNotice` 声称在非 Home Assistant 平台使用其云接口构成侵权；本项目为个人自用与学习目的，请自行评估。

MIT License。
