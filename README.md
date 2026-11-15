# TVDesk

给电视用的「干净桌面」：**基于 [CleanYourTV](https://github.com/Gathin23/cleanyourtv)（MIT）改造**，我自己那台 **Skyworth 65G5D** 上实际在用的版本。

除了原版桌面（应用抽屉、壁纸、时钟、天气），加了三个可选的页面——**推荐 / 影视 / 直播**。
**不配置就是纯桌面**：代码里没有任何硬编码的服务器地址或密钥，全部由你自己在电视上填。

<p>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-cfffcf?labelColor=15181e">
  <img alt="armeabi-v7a" src="https://img.shields.io/badge/ABI-armeabi--v7a-cfffcf?labelColor=15181e">
  <img alt="MIT" src="https://img.shields.io/badge/license-MIT-cfffcf?labelColor=15181e">
</p>

---

## 三个可选页面

| 页面 | 需要什么 | 不配置时 |
|---|---|---|
| **推荐** | MoviePilot（片单）+ 2bt0 资源库（海报、磁力） | 顶部不显示「推荐」tab |
| **影视** | Jellyfin 地址 + API 令牌 + 用户 ID | 顶部不显示「影视」tab |
| **直播** | 一个 M3U 订阅地址（可选 EPG） | 直播入口为空，不抓节目单 |

页面显隐是在启动时根据配置决定的，填完重启桌面即可看到。

---

## 快速开始

```bash
# 1. 装
adb connect 192.168.31.123:5555        # 换成你电视的 IP
adb install -r TVDesk.apk

# 2. 设为默认桌面（按 Home 直接回 TVDesk）
adb shell cmd package set-home-activity com.gathin.tvhome/.MainActivity
```

第一次启动就是干净的桌面：应用抽屉、最近观看、HDMI 源切换、天气、壁纸轮播。

---

## 配置（两种方式，任选）

### 方式一：电视上的设置页

桌面右上角 **设置 → TVDesk 设置 → 服务接入**，逐项用遥控器输入：

```
Jellyfin 地址        http://<NAS-IP>:8096
Jellyfin 令牌        <API Key>
Jellyfin 用户 ID     <User Id>
MoviePilot 地址      http://<NAS-IP>:3000
MoviePilot Key       <API Key>
2bt0 资源库地址      http://<NAS-IP>:8000
2bt0 Key             <X-API-Key>
直播源 M3U           http://<NAS-IP>:8080/list.m3u
EPG 地址             http://<EPG 服务>/t.xml.gz
```

留空 = 关闭该功能；「清空全部接入配置」可以一键回到纯桌面。

### 方式二：推一个 `desk.json`（推荐，换机/重装一条命令搞定）

```bash
# 本地按 desk.example.json 填好后：
adb push desk.json /sdcard/Android/data/com.gathin.tvhome/files/desk.json
adb shell am force-stop com.gathin.tvhome
adb shell am start -n com.gathin.tvhome/.MainActivity
```

`desk.json` 会覆盖设置页里的值，字段全部可选：

```json
{
  "jfBase":  "http://192.168.1.10:8096",
  "jfToken": "...",
  "jfUser":  "...",
  "mpBase":  "http://192.168.1.10:3000",
  "mpKey":   "...",
  "libBase": "http://192.168.1.10:8000",
  "libKey":  "...",
  "liveM3u": "http://192.168.1.10:8080/list.m3u",
  "epgUrl":  "http://epg.example.com/t.xml.gz"
}
```

> `desk.json` 已在 `.gitignore` 里，不会被提交。仓库里只有 `desk.example.json` 占位符。

### 三处配置从哪来

| 值 | 去哪拿 |
|---|---|
| Jellyfin 令牌 | Jellyfin 后台 → 控制台 → **API 密钥** → 新建 |
| Jellyfin 用户 ID | 后台 → 用户 → 点开用户，浏览器地址里 `userId=` 后面那串 |
| MoviePilot Key | MoviePilot → 设置 → **API** → API Key |
| 2bt0 Key | 2bt0 资源库的 `X-API-Key`（Docker 环境变量里那个） |
| 直播源 M3U | 任意 M3U 订阅地址；用 [iptv-manager](https://github.com/DTQEight/iptv-manager) 的话就是 `http://<NAS-IP>:8080/list.m3u` |

---

## 我这台电视（Skyworth 65G5D）的完整配置

> 型号 `1AA45_G5D`，Amlogic T963，Android 9（SDK 28），1920×1080 @ 240dpi，armeabi-v7a。

### 1. 开 ADB

设置 → 关于 → 连续点「版本号」进开发者模式 → 打开 **USB 调试 / 网络调试**。
电视固定 IP 后：

```bash
adb connect 192.168.31.123:5555
adb devices            # 出现 192.168.31.123:5555  device 就通了
```

掉线就重连：`adb disconnect && adb connect 192.168.31.123:5555`。

### 2. 装桌面 + 设为默认

```bash
adb install -r TVDesk.apk
adb shell cmd package set-home-activity com.gathin.tvhome/.MainActivity
adb shell am start -n com.gathin.tvhome/.MainActivity
```

按遥控器 Home 应该直接回 TVDesk，不再进原厂首页/开机广告。

### 3. 开机直达直播

TVDesk 的「直播」走自建 M3U（不是运营商那个 IPTV App），所以：

```json
{ "liveM3u": "http://192.168.31.52:8080/list.m3u" }
```

这个地址就是 NAS 上 [iptv-manager](https://github.com/DTQEight/iptv-manager) 容器（内置 udpxy）生成的订阅，
里面每条都是 `http://192.168.31.52:4022/rtp/239.x.x.x:1025` 这种 http-ts 地址。

> 我的 IPTV 是 VLAN 30 专网，udpxy 必须绑 `eth0.30`，否则一个字节都收不到 —— 这个坑写在
> [iptv-manager 的 README](https://github.com/DTQEight/iptv-manager#多-vlan--iptv-专网重要) 里。

### 4. 填我这边实际用的值

```bash
cat > desk.json <<'EOF'
{
  "jfBase":  "http://192.168.31.52:8096",
  "jfToken": "<你的 Jellyfin API Key>",
  "jfUser":  "<你的 Jellyfin 用户 ID>",
  "mpBase":  "http://192.168.31.52:3000",
  "mpKey":   "<MoviePilot API Key>",
  "libBase": "http://192.168.31.52:8000",
  "libKey":  "<2bt0 X-API-Key>",
  "liveM3u": "http://192.168.31.52:8080/list.m3u",
  "epgUrl":  "http://epg.808993.xyz/t.xml.gz"
}
EOF

adb push desk.json /sdcard/Android/data/com.gathin.tvhome/files/desk.json
adb shell am force-stop com.gathin.tvhome
adb shell am start -n com.gathin.tvhome/.MainActivity
```

起来后顶部应该是 **主页 | 推荐 › | 影视**，主页「最近观看」直接显示 Jellyfin 的续播进度。

### 5. 验证

```bash
adb shell "logcat -d | grep CYT-CFG"
# I CYT-CFG : 已读取预置配置 /storage/emulated/0/Android/data/com.gathin.tvhome/files/desk.json
# I CYT-CFG : jellyfin=true moviepilot=true 2bt0=true live=true
```

四项全 `false` 就说明没读到配置（检查路径 / JSON 拼写）。

---

## 权限从哪来（这台机器的实测链路）

电视本身没给 root：`user` 版本、`ro.secure=1`、没有 `su`、`adb root` 不可用。
但固件同时开了三道闸门，叠起来就能在不 root 的前提下接管桌面：

1. **出厂开着网络 ADB**（`service.adb.tcp.port=5555`）→ 局域网内直接拿到 `shell`
2. **SELinux 是 `Permissive`** → 策略只记日志、不拦截
3. **普通 App 能写 `persist.*` 属性** → 而厂商的开机/Home 服务恰好读 `persist.service.homepage.pkg` 决定拉起谁

```
ADB:5555 → shell(uid 2000) → 装 APK(uid 10029)
        → App 里 Runtime.exec("setprop", "persist.service.homepage.pkg", "com.gathin.tvhome")
        → SELinux Permissive 放行 → 写入 /data/property（跨重启保留）
        → 创维 HomeKey/开机服务读属性 → 开机和 Home 都进 TVDesk
```

实测证据（logcat 原文）：

```console
E sky_prop: uid[10029] gid[10029] pid[20051] PROPERTY SET [persist.service.homepage.pkg]: [com.gathin.tvhome]
```

`uid 10029` 就是本 App（`userId=10029`），它只申请了 `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `ACCESS_FINE_LOCATION`，
没有任何特权权限——**写入成功不是因为它有权限，而是因为 SELinux 没生效**。

重启后依旧指向 TVDesk（`ResumedActivity: com.gathin.tvhome/.MainActivity`，无原厂首页、无开机广告）。

顺带发现两处能力外溢：厂商 provider `content://com.skyworth.tv.sdk.provider` 对三方 App 无鉴权（能随意切 HDMI 源），
以及 `/system/bin` 里带着 `skybusybox`。

> 完整的发现过程、每一步的命令与原始输出、影响面与加固建议，都在 **[PRIVILEGE-AND-TAKEOVER.md](PRIVILEGE-AND-TAKEOVER.md)**。
> 只适用于我这一台机器（`1AA45_G5D`）——**请不要拿去做不属于你的设备**，同类问题建议先报厂商。

## 遥控器操作

| 位置 | 按键 | 行为 |
|---|---|---|
| 主页顶部 | ↑ | 焦点回顶部 tab 条 |
| 顶部 tab | ← → / OK | 切 主页 / 推荐 / 影视 |
| 推荐页 | OK | 进详情（简介 + 资源列表） |
| 影视页 | ← → | 左栏库列表 ↔ 右侧海报墙 |
| 影视页 | ↑ | 回顶部 tab |
| 播放页 | ← → / OK | 快退快进 / 暂停 |
| 播放页 | BACK | 退出播放（进度回报给 Jellyfin） |

---

## 构建

```bash
# 需要 JDK 17 + Gradle 8.9（用自带的 wrapper 也行）
cd launcher
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- `compileSdk 35 / minSdk 26 / targetSdk 34`
- ABI：`armeabi-v7a`（盒子/电视基本都是这个）
- 播放器用 ijkplayer + MediaCodec 硬解，`libijkffmpeg.so` 占安装包 10 MB
- 仓库里不带签名文件；自己出包请在 `launcher/local.properties` 配 keystore

---

## 目录结构

```
launcher/app/src/main/java/com/gathin/tvhome/
├── MainActivity.java          桌面主界面 + 三页 ViewPager
├── DeskConfig.java            接入配置（设置页 / desk.json → 各客户端）
├── JellyfinClient.java        Jellyfin API（浏览、续播、播放地址、字幕）
├── JellyfinView.java          影视页：左栏库 + 海报墙 + 详情
├── JellyfinPlayerActivity.java ijkplayer 播放器
├── MpClient.java              MoviePilot 推荐流
├── LibClient.java             2bt0 资源库（海报 / 磁力 / 转存）
├── RecommendView.java         推荐页
├── LiveSource.java / Epg.java 直播源与节目单
└── LivePlayerActivity.java    直播播放页
```

---

## 已知限制

- **只支持 `armeabi-v7a`**：我自己这台是 32 位盒子；64 位机型要改 `abiFilters`
- **海报道理走 Clash**（`设置 → 推荐页图片代理`）：资源库没命中的图才走代理，关掉不影响使用
- **Jellyfin 的 eac3/ac3/dts 音轨会自动转码**成 aac 2.0，因为这台电视直通没声；代价是起播慢 1~2 秒
- 原厂开机广告只能靠「替换默认桌面 + 禁用原厂桌面」规避，Root 级去广告不在本项目范围

---

## 致谢 / 许可

- 上游项目：[**Gathin23/cleanyourtv**](https://github.com/Gathin23/cleanyourtv)（MIT，Copyright © 2026 Gathin）—— 桌面骨架、应用抽屉、壁纸、天气都来自它
- 播放器：[ijkplayer](https://github.com/bilibili/ijkplayer)（Apache-2.0）
- 本仓库改动同样以 **MIT** 发布，见 [LICENSE](LICENSE)

上游原始的说明文档保留在 [README.upstream.md](README.upstream.md)。
