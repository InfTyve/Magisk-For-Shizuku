# Magisk canary-27007 · Shizuku Root 支持

本仓库基于 [Magisk](https://github.com/topjohnwu/Magisk) `canary-27007` 源码修改，
让 Magisk App 在没有 `su` 可用的系统上，通过**以 root 身份运行的 Shizuku** 取得
root shell，从而完成「直接安装」（刷写 boot / init_boot 分区）。

---

## 一、为什么需要这个改动

Magisk App 的所有特权操作都依赖 libsu 的主 shell：

```
app  --su--> /system/bin/su --binder--> magiskd
```

如果系统本身有 root（例如工程机 `adb root`、内核自带 root、或只提供命令行 root），
但**没有 Magisk 的 su 守护进程**，libsu 拿不到 root shell，App 只会显示「未安装」，
安装页也没有「直接安装」选项，只能修补 boot 文件再由别的方式刷入。

而 Shizuku 可以以 root 身份启动（服务端 uid = 0），并通过 binder 为已授权应用
fork 出 uid = 0 的进程。把这条通道接进 libsu，Magisk 就能像平时一样执行
`find_boot_image` / `boot_patch.sh` / `direct_install`。

## 二、实现原理

0. **开关**：设置页「使用 Shizuku 获取 root」默认**关闭**。关闭时完全走原生
   `su` 流程；打开后重启应用才生效。
1. **探测**：`ShizukuRoot.prepare()` 在启动闪屏阶段检查
   `pingBinder()` → `checkSelfPermission()` → `getUid() == 0`。
2. **授权**：未授权时调用 `Shizuku.requestPermission()`，回调监听器在授权通过后
   继续初始化（不重启进程）。
3. **取 root shell**：
   ```kotlin
   Shizuku.newProcess(arrayOf("/system/bin/sh"), null, "/")
   ```
   该 API 在 Shizuku 13.x 中被标记为 private，这里通过反射调用。
4. **接入 libsu**：
   ```kotlin
   Shell.Builder.create()
       .setInitializers(ShellInit::class.java)
       .build(process)          // 由 libsu 做 shell 探测（id → uid=0）
   ```
   再用反射把得到的 `Shell` 写入 `com.topjohnwu.superuser.internal.MainShell`
   的 `mainShell[0]` 缓存，替换全局主 shell。
5. 之后 Magisk 中所有 `Shell.cmd(...)`、`ShellUtils.fastCmd(...)`、
   `MagiskInstallImpl` 里的脚本调用，全部在这个 root shell 中执行，
   包括 `direct_install` 中的 `cat new-boot.img | cat - /dev/zero > /dev/block/by-name/boot`。

### libsu root service 的取舍

原版 Magisk 还会通过 root shell 启动一个 libsu `RootService`（用于以 root 身份
访问文件系统、读取进程列表等）。这条路径依赖 `su` 启动 `app_process`，
在 Shizuku 环境下不可靠，因此：

- Shizuku 模式下跳过 `RootService` 绑定；
- `RootUtils.fs` 退化为 App 自身的 `FileSystemManager.getLocal()`；
- `Info.noDataExec` 强制为 `false`（root shell 可以直接执行 /data 下的二进制）。

安装流程本身不依赖 root 文件系统：`boot_patch.sh`、`direct_install` 都在
root shell 里跑，路径以字符串传递。

## 三、改动文件

| 文件 | 改动 |
| --- | --- |
| `app/core/utils/ShizukuRoot.kt` | **新增**。Shizuku 探测、授权、root shell 注入、`ShizukuProcess` 包装 |
| `app/core/Config.kt` | 新增 `shizukuRoot` 开关（默认 `false`），存于 SharedPreferences |
| `app/apk/ui/settings/SettingsItems.kt` | 新增 `ShizukuRootToggle` 设置项 |
| `app/apk/ui/settings/SettingsViewModel.kt` | 设置列表中挂载开关，切换后提示重启 / 提示 Shizuku 不可用 |
| `app/core/AppContext.kt` | 移除启动时的主 shell 预热（避免非 root shell 抢先被缓存） |
| `app/core/utils/ShellInit.kt` | Shizuku 模式跳过 root service、跳过 `noDataExec` 探测 |
| `app/core/utils/RootUtils.kt` | Shizuku 模式下 `Connection.await()` 直接返回，避免死锁 |
| `app/core/base/SplashScreen.kt` | 创建主 shell 前先走 `ShizukuRoot.prepare()` |
| `app/apk/AndroidManifest.xml` | 注册 `rikka.shizuku.ShizukuProvider`，`tools:overrideLibrary` |
| `app/apk/proguard-rules.pro` | 保留反射目标（`MainShell`、`ShellImpl`、`rikka.shizuku.**`） |
| `app/core/build.gradle.kts`、`gradle/libs.versions.toml` | 引入 `dev.rikka.shizuku:api/provider:13.1.5` |
| `app/core/res/values{,-zh-rCN}/strings.xml` | 新增开关标题/描述、「未以 root 运行」、「重启生效」等文案 |
| `buildSrc/Setup.kt` | `syncLibs` 支持 `libbusybox.so`；busybox 已存在时跳过下载 |
| `config.prop` | 版本名 `27007-shizuku`、`versionCode=2700701`、ABI 列表 |

## 四、编译

本仓库已放入**从官方 canary-27007 APK 提取的 native 二进制**
（`native/out/<abi>/{magisk,magiskboot,magiskinit,magiskpolicy,libinit-ld.so,libbusybox.so}`），
因此**不需要编译 native 代码**，也无需下载 submodule。

环境要求：

- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`
- NDK：`27.0.12077973`，路径必须是 `$ANDROID_HOME/ndk/magisk`
  （`:native` 模块在配置阶段会校验该路径，即使不编译 native 也必须存在）

构建：

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:apk:assembleDebug      # 产物 app/apk/build/outputs/apk/debug/apk-debug.apk
./gradlew :app:apk:assembleRelease    # 产物 app/apk/build/outputs/apk/release/apk-release.apk
```

> 如需重新编译 native 二进制：`git submodule update --init --recursive` 后执行
> `./build.py ndk && ./build.py binary`（需要 topjohnwu 的 ondk + Rust 工具链）。

## 五、使用步骤

1. 用 **root 权限**启动 Shizuku：
   - Shizuku 内「通过 root 启动」，或
   - `adb shell` 下以 root 执行 Shizuku 的 `start.sh`。
2. 确认 Shizuku 服务端 uid 为 0（Shizuku 首页会显示「Root」）。
3. 安装本 APK（调试签名与原版不同，需先卸载原版 Magisk）。
4. 打开 Magisk → 设置 → 打开「**使用 Shizuku 获取 root**」开关 → 按提示重启应用。
5. 回到应用时会弹出 Shizuku 授权框，选择**允许**（授权后无需再重启，直接生效）。
6. 进入「安装」→ 选择「**直接安装**」→ 开始。日志中应能看到：
   ```
   - Target image: /dev/block/by-name/boot
   - Flashing new boot image
   ```
7. 刷写完成后重启生效。

> 关闭开关后行为与原版 Magisk 完全一致（走 `su`），此后不再向 Shizuku 申请任何权限。

## 六、限制与排错

| 现象 | 原因 / 处理 |
| --- | --- |
| 设置里没有「使用 Shizuku 获取 root」 | 当前构建未包含该功能，或应用为旧版本 |
| 开关打开后无变化 | 需要重启应用（开关提示「重启应用后生效」） |
| 提示「Shizuku 未以 root 运行」 | Shizuku 服务端不是 uid 0，需要用 root 启动 Shizuku |
| 安装页没有「直接安装」 | Shizuku 未授权或未以 root 运行；查看 `logcat -s Magisk` |
| `! /dev/block/by-name/boot is read only` | SELinux 拒绝写块设备：Shizuku 的 domain（如 `u:r:shell:s0`）权限不足 |
| `! Insufficient partition size` | 目标分区太小，通常说明找错了分区 |
| 刷入成功但开机无 Magisk | `fix_env` 未能写 `/data/adb/magisk`（SELinux 限制）；boot 已 patch，需在可写环境下补装 |
| Android 6.0（API 23） | 不支持，会自动回退到原生 su 流程 |

> 写块设备的能力完全取决于 Shizuku 进程的 SELinux 上下文。若为 `u:r:shell:s0`，
> 通常无法写 `boot` 分区；以 `su` 域（`u:r:su:s0`）或 permissive 环境启动 Shizuku
> 才能成功。

## 七、安全性说明

- 开关**默认关闭**，不主动申请任何 Shizuku 权限。
- 仅在用户打开开关并明确授权后才会向 Shizuku 申请权限，且要求 Shizuku 以 root 运行。
- 未安装 / 未运行 Shizuku 时，行为与原版 Magisk 完全一致（走 `su` → `sh`）。
- 反射调用的 `Shizuku.newProcess` 与 `MainShell` 字段已加入 ProGuard keep 规则。
