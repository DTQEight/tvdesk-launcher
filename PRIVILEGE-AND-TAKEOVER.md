# 这台电视的权限边界，以及桌面是怎么被接管的

> **责任声明**
> 下面全部是在**我自己花钱买的这台电视**上测的：创维 65G5D（`1AA45_G5D` / Amlogic T963 / Android 9 / 32 位）。
> 目的只有一个——在一台不给自己 root 的机器上，把开机广告和原厂首页换掉。
>
> - 这些配置缺陷属于**厂商固件问题**，不是我的授权范围。如果你在别的机器上发现同样问题，**请先报给厂商**（创维/酷开），别拿去动别人的设备。
> - 下面每一步都在我自己的机器上可复现，命令和输出都是原始记录；换机型/换固件可能完全不同。
> - 我没有拿到 root，也没改 `/system` 一个字节。

---

## 一句话结论

这台机器**没有传统意义上的提权漏洞可利用**（`user` 版本、`ro.secure=1`、无 `su`、`adb root` 不可用），
但固件把三道本该独立的闸门同时敞开了：

1. **出厂就开着网络 ADB**（`service.adb.tcp.port=5555`），局域网内谁都能拿到 `shell`；
2. **SELinux 是 Permissive**，只有日志没有拦截；
3. **普通 App 能写 `persist.*` 系统属性**，而厂商的开机/Home 服务恰好读这几条属性决定「拉起谁」。

三条叠起来 = **不 root、不动系统分区，也能让电视开机和按 Home 都进我的桌面**，且重启后依然生效。

---

## 一、这台机器的底子

| 项 | 实测值 |
|---|---|
| 型号 | `1AA45_G5D`（创维 65G5D） |
| 系统 | Android 9 / SDK 28 |
| 构建类型 | `ro.build.type=user`、`ro.secure=1`、`ro.debuggable=0` |
| root | 无 `su`，`adb root` 不可用 |
| SELinux | **`Permissive`**（`ro.boot.selinux=permissive`） |
| 网络调试 | `adb_enabled=1`、`service.adb.tcp.port=5555` |
| adb shell 身份 | `uid=2000(shell)` |
| 我的桌面 | `com.gathin.tvhome`，`uid=10029` |

关键一条：

```console
$ adb shell getenforce
Permissive
$ adb shell getprop ro.boot.selinux
permissive
```

Android 从 4.3 起预期是 `Enforcing`；**出厂固件开成 Permissive，等于所有 SELinux 策略都只写日志不生效**。这一条是后面所有事的底座。

---

## 二、怎么发现的（四步，都能自己复现）

### 第 1 步：`getprop` 里有一串「决定开机拉起谁」的属性

```console
$ adb shell getprop | grep -E 'homepage|custom|service\.'
[persist.service.homepage.pkg]: [com.skyworth.officehomepage]
[persist.service.homepage.cls]: [com.skyworth.officehomepage.MainActivity]
[persist.service.model.pkg]: [...]
[persist.sys.custom.pkg]: [...]
[persist.service.starthomepage]: [1]
```

`persist.*` = 写进 `/data/property/`，**重启不清**。命名一眼能看出用途：`homepage.pkg/.cls` 就是「开机/Home 拉起哪个包哪个 Activity」。

厂商自己的系统进程（`uid=1000`）确实会写它：

```console
E sky_prop: uid[1000] gid[1000] pid[3946] PROPERTY SET [persist.service.starthomepage]: [1]
```

### 第 2 步：`logcat` 里发现写属性的 uid 不是 1000

装好自己的桌面之后，随手 `logcat` 一看——**同样的属性，写入方是我的 App（uid 10029）**：

```console
$ adb shell logcat -d | grep sky_prop
E sky_prop: uid[10029] gid[10029] pid[20051] PROPERTY SET [persist.service.homepage.pkg]: [com.gathin.tvhome]
E sky_prop: uid[10029] gid[10029] pid[20053] PROPERTY SET [persist.service.homepage.cls]: [com.gathin.tvhome.MainActivity]
E sky_prop: uid[10029] gid[10029] pid[20058] PROPERTY SET [persist.sys.custom.pkg]: [com.gathin.tvhome]
E sky_prop: uid[10029] gid[10029] pid[20060] PROPERTY SET [persist.sys.custom.cls]: [com.gathin.tvhome.MainActivity]
```

`uid[10029]` 就是 `com.gathin.tvhome`（`dumpsys package com.gathin.tvhome` → `userId=10029`）。

**一个普通三方 App，写成功了 `persist.*`。** 正常情况下这在 Enforcing 的 Android 上是不可能的：
`untrusted_app` 域对 `persist.*` 属性没有写权限，`setprop` 会被 prop 服务 + SELinux 一起拒掉。

### 第 3 步：查 SELinux 状态，确认原因

```console
$ adb shell getenforce
Permissive
```

Permissive 模式下 SELinux 只记 `avc: denied` 日志、不真的拦。所以：

- `untrusted_app` 写 `persist.*` → 放行
- `shell` 写任意属性 → 放行
- `/system/bin/setprop` 是原厂 toybox 的软链（没被改过），问题不在工具，在策略

### 第 4 步：验证「真的不需要任何特权」

我的 App 只申请了这几个权限：

```console
$ adb shell dumpsys package com.gathin.tvhome
requested permissions:
      android.permission.ACCESS_NETWORK_STATE
      android.permission.ACCESS_WIFI_STATE
      android.permission.ACCESS_FINE_LOCATION
      android.permission.INTERNET
```

没有 `WRITE_SECURE_SETTINGS`、没有 `signature|privileged`、不是系统 App、装在 `/data/app`。
写属性的代码就三行（`MainActivity.armHomeProps()`）：

```java
Runtime.getRuntime().exec(new String[]{"setprop", p[0], p[1]}).waitFor();
```

---

## 三、完整链路

```
┌─ 入口：出厂开着的网络 ADB ────────────────────────────────────┐
│  service.adb.tcp.port=5555   adb_enabled=1                    │
│  局域网内 adb connect 192.168.31.123:5555 → uid 2000 shell     │
└───────────────┬───────────────────────────────────────────────┘
                │ ① shell 层（一次性）
                ▼
     adb install TVDesk.apk                      装进 /data/app，uid 10029
     cmd package set-home-activity ...           把默认桌面指向 TVDesk
     pm disable-user --user 0 com.coocaa.os.upgrade   掐掉升级/广告组件
                │
                │ ② App 层（每次回到前台补一次，代价为零）
                ▼
     Runtime.exec("setprop",
        persist.service.homepage.pkg  com.gathin.tvhome
        persist.service.homepage.cls  com.gathin.tvhome.MainActivity
        persist.service.model.pkg     com.gathin.tvhome
        persist.sys.custom.pkg/.cls   com.gathin.tvhome(.MainActivity))
                │
                │ ③ SELinux Permissive：本该被拒的写入被放行
                ▼
     property_service 写入 /data/property/persist.service.homepage.pkg
                │ （persist 前缀 = 落盘，重启不清）
                ▼
     创维 HomeKey / 开机服务读这两条属性 → 决定拉起谁
                │
                ▼
     开机 & 按 Home 键 → 直接进 TVDesk，原厂首页/开机广告不再出现
```

### 为什么这一套能「不 root」成立

| 环节 | 正常 Android 上应该怎样 | 这台机器实际 |
|---|---|---|
| 三方 App 写 `persist.*` | SELinux 拒绝（`untrusted_app`） | Permissive，放行 |
| 改默认桌面 `set-home-activity` | 需要 shell/root | ADB 默认开着，shell 随手就有 |
| 停用系统组件 `pm disable-user` | 需要 shell/root | 同上 |
| 开机拉起谁 | 由 `ActivityManager` 的 HOME Intent 决定 | **厂商另有一套 `persist.service.homepage.*` 覆盖它** |

最后一行是这台机器真正「特殊」的地方：**厂商自己加了一套并行于 AOSP 的首页决策机制**，而这套机制的输入是普通 App 能写的属性。

---

## 四、重启验证（实测）

```console
$ adb shell setprop persist.sys.tvdesk.test keep-me      # 先埋一个哨兵
$ adb shell getprop persist.service.homepage.pkg
com.gathin.tvhome

$ adb reboot
...（约 75 秒）

$ adb shell getprop persist.service.homepage.pkg
com.gathin.tvhome                                        # ✓ 没被清
$ adb shell getprop persist.sys.tvdesk.test
keep-me                                                   # ✓ persist 语义正常
$ adb shell dumpsys activity activities | grep ResumedActivity
ResumedActivity: ActivityRecord{5392fa9 u0 com.gathin.tvhome/.MainActivity t1}
```

**开机直接落在 TVDesk**，没有原厂首页，也没有开机广告。

> 补一句踩坑记录：代码注释里写过「开机会被清空，所以每次回前台补一次」。实测这条属性其实能跨重启，
> 但不同固件/不同字段行为不一致，所以 `armHomeProps()` 仍然每次 `onResume` 补一遍——成本是一次 `exec`，不心疼。

---

## 五、顺带发现的两处「越权面」

### 1. 厂商 ContentProvider 对三方 App 无鉴权

电视切 HDMI / 回 TV 源用的是厂商 provider，我的 App 直接调，**没申请任何权限**：

```java
private static final Uri URI = Uri.parse("content://com.skyworth.tv.sdk.provider/method");

ctx.getContentResolver().call(URI, "METHOD_GOTO_SOURCE", null, bundle);      // 切到指定输入源
ctx.getContentResolver().call(URI, "METHOD_FORCE_RELEASE_SOURCE", null, null); // 释放当前源
ctx.getContentResolver().call(URI, "METHOD_BACK_TO_TV", null, null);           // 回电视
```

logcat 里能看厂商进程老实执行了：

```console
D TV-TV-TVSDKContentProvider: call>from:com.gathin.tvhome ,call>method:METHOD_FORCE_RELEASE_SOURCE
D TV-SkyTVManager: tryReleaseSource
D TV-SkyTVManager: source has been released
```

结论：**任何装在这台电视上的 App，都能随意切输入源、抢/放电视画面**。这是能力外溢，正常应该用 signature 权限保护。

### 2. `/system/bin` 里躺着 `skybusybox`

```console
$ adb shell ls /system/bin | grep -i sky
skyavdebug
skybusybox
skyrmserver
```

`skybusybox` + Permissive，等于给了个现成的工具箱。我没有用它（也不需要），但这条在评估影响面时必须算进去。

---

## 六、边界：能做什么，不能做什么

| 能做（不 root） | 不能做 |
|---|---|
| 改默认桌面、开机拉起谁 | 改 `/system`、`/vendor`（只读，且没有 remount 权限） |
| `pm disable-user` 停系统组件 | `adb root`（`ro.debuggable=0`） |
| 写任意 `persist.*` 属性 | 读 `/data/property`（内核级 root 才能进，`shell` 被拒） |
| 调厂商 provider 切源/放源 | 装 Magisk / 改 boot.img |
| 抓全量 logcat、`dumpsys`、`uiautomator` | 绕过付费/DRM（没试过，也不打算） |

一句话：**拿到的是一台「可完全自定义、但没越出 Android 权限模型」的电视。**

---

## 七、加固建议

**用户侧（减少暴露面）**

```bash
adb shell settings put global adb_enabled 0        # 关掉网络调试
adb shell setprop service.adb.tcp.port -1          # 下次开机别再监听 5555
```
或者直接把电视放在访客 VLAN 里，只允许自己电脑的 IP 访问 5555。

**厂商侧（真正的修法）**

1. 出厂 `ro.boot.selinux=enforcing`，别发 Permissive 固件；
2. `persist.service.*` / `persist.sys.custom.*` 这些属性加 `system|root` 专属 context，`untrusted_app` 一律拒绝；
3. 首页决策不要再靠「App 可写的全局属性」，走 `ActivityManager` 的 HOME Intent + `role` 机制；
4. `com.skyworth.tv.sdk.provider` 加 signature 级权限。

---

## 八、如果你想自己复现/取证

```bash
# 1) 看根因
adb shell getenforce
adb shell getprop ro.boot.selinux

# 2) 看厂商的首页机制
adb shell getprop | grep -E 'persist\.(service|sys)'

# 3) 看谁在写这些属性（装个普通 App 后停它、起它，对比 logcat）
adb shell logcat -c && adb shell am force-stop com.gathin.tvhome
adb shell am start -n com.gathin.tvhome/.MainActivity
adb shell logcat -d | grep sky_prop

# 4) 看 App 有没有特权
adb shell dumpsys package com.gathin.tvhome | sed -n '/requested permissions/,/^$/p'

# 5) 验证持久化
adb shell setprop persist.sys.tvdesk.test keep-me
adb reboot && sleep 75 && adb shell getprop persist.sys.tvdesk.test
```

---

## 附：本项目实际用到的三条「越权」

| 用途 | 走的是哪条路 | 需要的权限 |
|---|---|---|
| 开机 / Home 直达 TVDesk | App 写 `persist.service.homepage.*` | 无（靠 Permissive） |
| 去掉原厂首页与广告 | `pm disable-user` + `set-home-activity` | ADB shell |
| 桌面里切 HDMI / 回电视 | 厂商 provider `com.skyworth.tv.sdk.provider` | 无（provider 没校验） |

> 本文只描述我这一台机器上的实测结果。**不要拿去操作不属于你的设备。**
