# Podroid APK 构建 SOP（给 AI 助手看）

> **你为什么看到这个文件**：用户请你帮忙构建 Podroid APK。这是一个完整的操作手册。
> 请通读全文后再开始操作。不要猜测，不要跳步。遇到不确定的地方先问用户。
>
> **用户规则**：
> - 多确认多询问，切勿自作主张完成整个流程
> - 终端命令使用 cmd 风格（不是 PowerShell）
> - git commit/push 必须先用 AskQuestion 确认，commit message 用英文
> - 禁止在超过 1000 行的代码中新增大篇幅片段

---

## 项目是什么

Podroid 是一个 Android App，在手机上通过 QEMU 运行 Alpine Linux VM，提供无 root 的 Podman/Docker 环境。

用户在此基础上做了两项定制：
1. **QMI 内核驱动**：`CONFIG_USB_NET_QMI_WWAN=y`，让 VM 支持 USB 网卡（用于 VoWiFi 通话）
2. **vohive 应用扩展**：在 App 主页增加 vohive 快捷入口（HTTP API 探活 + WebUI 跳转 + 默认 7575 端口转发）

---

## 产物在哪里、去哪里

APK 不是纯代码编译，它需要打包 **VM 二进制文件**（内核、initramfs、rootfs、QEMU 库）进 APK 的 assets 目录。

```
最终 APK 内部结构（相关部分）：

app-debug.apk
└── assets/
    ├── vmlinuz-virt              ← 定制 Linux 内核（aarch64）
    ├── initrd.img                ← Alpine initramfs
    ├── alpine-rootfs.squashfs   ← Alpine rootfs
    └── qemu/
        ├── efi-virtio.rom
        └── keymaps/
└── lib/arm64-v8a/
    ├── libqemu-system-aarch64.so ← QEMU 本体
    ├── libslirp.so
    ├── libpodroid-bridge.so
    └── libpodroid-launcher.so
```

这些 VM 二进制**通过 Docker 构建**生成（在 R0 服务器上），然后下载到本地，放到 Podroid 仓库的对应目录，最后用 Gradle 打包成 APK。

---

## 两阶段流程

```
阶段 1（R0 服务器）              阶段 2（本地 Windows）
┌─────────────────────┐         ┌──────────────────────┐
│ Docker 构建          │         │ Gradle 编译 APK       │
│                     │         │                      │
│ 1. packer           │──下载──→│ 1. 放置产物到仓库      │
│    → vmlinuz-virt   │         │ 2. gradlew assemble   │
│    → initrd.img     │         │    → app-debug.apk    │
│ 2. rootfs           │         │                      │
│    → squashfs       │         │                      │
│ 3. qemu             │         │                      │
│    → 4个.so + ROM   │         │                      │
│ 4. tar 打包          │         │                      │
└─────────────────────┘         └──────────────────────┘
```

---

## 环境信息

### R0 服务器（通过 SSH MCP 工具连接）

- 连接方式：使用 `mcp_tool_R0_*` 系列工具（execute-command, job-start, job-status 等）
- 系统：x86_64 Linux，有 Docker（26.1.5），16 核 CPU
- Podroid 仓库位置：`/tmp/Podroid`
- 产物输出位置：`/tmp/podroid-assets/`
- **注意**：R0 上没有 Android SDK/NDK，不能编译 APK

### 本地 Windows 电脑（通过 run_terminal_cmd 执行命令）

- 操作系统：Windows 11
- Shell：cmd（用户要求不用 PowerShell 命令）
- Podroid 仓库：`C:\Users\aunsn\OneDrive\Desktop\tmp\Podroid`
- JDK 17：`C:\jdk17\jdk-17.0.20.1+1`（需要手动设置 `JAVA_HOME`，见下方）
- Android SDK：`C:\Users\aunsn\AppData\Local\Android\Sdk`（AGP 会自动补全缺少的 platform 和 NDK）
- GitHub 仓库：`github.com/voorz/Podroid`

### JAVA_HOME 问题

`gradlew.bat` 需要找到 `java`。`JAVA_HOME` 虽然通过 `setx` 设置过，但新 shell 中经常不生效。
**解决办法**：每次执行 gradlew 前，在同一个 cmd 会话中先 `set "JAVA_HOME=..."`。
或者创建临时 bat 脚本：
```cmd
@echo off
set "JAVA_HOME=C:\jdk17\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d C:\Users\aunsn\OneDrive\Desktop\tmp\Podroid
call gradlew.bat assembleDebug
```

### local.properties

文件 `Podroid\local.properties` 必须存在，内容：
```properties
sdk.dir=C:\\Users\\aunsn\\AppData\\Local\\Android\\Sdk
```

---

## 阶段 1 详细操作（R0）

> 全部通过 `mcp_tool_R0_execute-command` 或 `mcp_tool_R0_job-start` 执行。
> 长时间构建用 `job-start`，然后轮询 `job-status`。

### 前置：注册 binfmt 解释器（仅首次或重启后）

R0 是 x86_64，但 Podroid 的 rootfs-builder 和 packer 阶段需要运行 aarch64 Alpine 容器。
如果报 `exec format error`，执行：
```bash
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
```

### 步骤 1: 构建内核 + initramfs

```bash
cd /tmp/Podroid
docker build --network=host \
    --build-arg KERNEL_VERSION=7.3 \
    -t podroid-builder --target packer .
```

这一步会编译 Linux 7.3 内核（aarch64 交叉编译），打包 Alpine initramfs。
如果内核源码已缓存，约 10 秒；首次或 `--no-cache` 约 10-15 分钟。

提取产物：
```bash
docker rm -f podroid-extract 2>/dev/null
docker create --name podroid-extract podroid-builder /bin/true
mkdir -p /tmp/podroid-assets
docker cp podroid-extract:/output/vmlinuz-virt /tmp/podroid-assets/vmlinuz-virt
docker cp podroid-extract:/output/initrd.img /tmp/podroid-assets/initrd.img
docker rm podroid-extract >/dev/null
```

### 步骤 2: 构建 rootfs

```bash
cd /tmp/Podroid
rm -rf build-rootfs/docs-guide
mkdir -p build-rootfs/docs-guide
cp docs/guide/*.html docs/guide/style.css build-rootfs/docs-guide/
docker build -f build-rootfs/Dockerfile.rootfs \
    -t podroid-rootfs:latest \
    --build-arg SYSTEM_VERSION=34 \
    --output type=local,dest=/tmp/podroid-assets \
    build-rootfs/
```

约 1-2 分钟。产物：`/tmp/podroid-assets/alpine-rootfs.squashfs`（约 216MB）

### 步骤 3: 构建 QEMU

```bash
cd /tmp/Podroid
docker build --build-arg QEMU_VERSION=11.1.2 \
    -t podroid-qemu-builder --target final .
```

这一步会同时编译内核和 QEMU，约 10 分钟。用 `job-start` 在后台运行。

提取产物：
```bash
docker rm -f podroid-qemu-extract 2>/dev/null
docker create --name podroid-qemu-extract podroid-qemu-builder /bin/true
mkdir -p /tmp/podroid-assets/jniLibs /tmp/podroid-assets/qemu/keymaps
docker cp podroid-qemu-extract:/libqemu-system-aarch64.so /tmp/podroid-assets/jniLibs/
docker cp podroid-qemu-extract:/libslirp.so               /tmp/podroid-assets/jniLibs/
docker cp podroid-qemu-extract:/libpodroid-bridge.so      /tmp/podroid-assets/jniLibs/
docker cp podroid-qemu-extract:/libpodroid-launcher.so    /tmp/podroid-assets/jniLibs/
docker cp podroid-qemu-extract:/qemu/efi-virtio.rom       /tmp/podroid-assets/qemu/
docker cp podroid-qemu-extract:/qemu/keymaps/.            /tmp/podroid-assets/qemu/keymaps/
docker rm podroid-qemu-extract >/dev/null
```

### 步骤 4: 打包

```bash
cd /tmp
tar -czf podroid-assets.tar.gz -C /tmp podroid-assets/
```

产物：`/tmp/podroid-assets.tar.gz`（约 313MB）

### 验证 QMI 驱动（可选但推荐）

```bash
# QMI 是内置编译（=y），不是模块，所以不会在 initrd 中有 .ko 文件
# 验证方法：解压内核镜像后搜索字符串
docker run --rm podroid-builder bash -c \
  'zcat /output/vmlinuz-virt | strings | grep -i "qmi_wwan\|usbnet\|cdc_ether" | head -20'
```

如果输出包含 `qmi_wwan`、`usbnet`、`cdc_ether` 等字符串，说明驱动已内置。

---

## 阶段 2 详细操作（本地）

### 步骤 1: 下载产物

**AI 无法从 R0 下载文件到本地**（MCP SSH 工具只能执行远程命令，不能跨机器传文件）。
需要告诉用户从 R0 下载 `/tmp/podroid-assets.tar.gz`，方式不限（FTP、SCP、WebDAV 等）。

用户下载后解压会得到 `podroid-assets/` 目录。
**注意**：解压后可能有嵌套目录 `podroid-assets/podroid-assets/`，这是 tar 打包的正常行为。
告诉用户解压后的路径，AI 来帮忙把文件放到 Podroid 仓库正确位置。

### 步骤 2: 放置产物到 Podroid 仓库

用 cmd 命令（假设解压目录为 `<dir>\podroid-assets\`）：

```cmd
:: 三个 VM 镜像文件 → assets/
copy <dir>\podroid-assets\vmlinuz-virt           Podroid\app\src\main\assets\
copy <dir>\podroid-assets\initrd.img              Podroid\app\src\main\assets\
copy <dir>\podroid-assets\alpine-rootfs.squashfs Podroid\app\src\main\assets\

:: 四个 .so 文件 → jniLibs/arm64-v8a/（注意子目录名是 arm64-v8a）
mkdir Podroid\app\src\main\jniLibs\arm64-v8a 2>nul
copy <dir>\podroid-assets\jniLibs\*.so            Podroid\app\src\main\jniLibs\arm64-v8a\

:: QEMU ROM + keymaps → assets/qemu/
copy /Y <dir>\podroid-assets\qemu\efi-virtio.rom  Podroid\app\src\main\assets\qemu\
xcopy /E /I /Y <dir>\podroid-assets\qemu\keymaps   Podroid\app\src\main\assets\qemu\keymaps\
```

### 步骤 3: 编译 APK

```cmd
set "JAVA_HOME=C:\jdk17\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d C:\Users\aunsn\OneDrive\Desktop\tmp\Podroid
gradlew.bat assembleDebug
```

- 首次构建约 7-8 分钟（下载 Gradle 9.3.1 + 依赖 + AGP 自动安装 NDK 和 platform 36）
- 后续构建约 1-2 分钟
- AGP 会自动安装缺少的 SDK Platform 36 和 NDK 28.2，无需手动操作

### 步骤 4: 验证产物

```cmd
dir Podroid\app\build\outputs\apk\debug\app-debug.apk
```

应该有一个约 320MB 的 `app-debug.apk`。

---

## 内核配置说明

### QMI 驱动

文件：`Podroid/podroid_kernel.config` 中有 `CONFIG_USB_NET_QMI_WWAN=y`

**=y 意味着内置编译**，不是模块。`qmi_wwan.ko` 不会出现在 initramfs 中。
同理 `cdc_ether`、`usbnet` 等相关 USB 网络驱动也全部内置编译进内核。

### 模块白名单

文件：`Podroid/Dockerfile`，第 180 行通过 `find ... | grep -vE '...'` 删除不需要的 `.ko` 模块。
白名单中保留了 `qmi_wwan|cdc_ether|cdc_ncm|cdc_mbim|usbnet` 的模块名，以防将来改为 `=m` 时不被删除。

**如果修改了白名单正则**，必须 `--no-cache` 重建，否则 Docker 会命中缓存：
```bash
docker build --network=host --no-cache \
    --build-arg KERNEL_VERSION=7.3 \
    -t podroid-kernel-builder --target kernel-builder .
```

---

## 已完成的代码改动

以下改动已 commit 并 push 到 `github.com/voorz/Podroid` main 分支：

| Commit | 说明 |
|--------|------|
| `e4e463b` | fix: force cache bust for module whitelist step |
| `b09ee7f` | feat: add vohive app extension section with HTTP API probe and default 7575 port forward |
| `bc2f828` | fix: explicit generic type for flowOf to resolve Kotlin type inference error |

### vohive 集成的文件清单

| 文件 | 改动内容 |
|------|----------|
| `PodroidService.kt` | 新增 `VOHIVE_HOST_PORT = 7575`，启动 VM 时默认注入端口转发 |
| `AppExtensionsSection.kt`（新建） | 应用扩展 UI 组件，支持 VmNotRunning/NotInstalled/InstalledNotRunning/Running/Unknown 五种状态 |
| `HomeScreen.kt` | 在 VM 状态和网络区块后集成 AppExtensionsSection |
| `HomeViewModel.kt` | HTTP 探活 `http://127.0.0.1:7575/api/system/info`，每 10 秒轮询 |
| `strings.xml` / `values-zh/strings.xml` | 双语本地化文案 |

### Kotlin 类型推断陷阱

`HomeViewModel.kt` 中 `flowOf(AppExtensionStatus.VmNotRunning)` 会被 Kotlin 推断为 `Flow<VmNotRunning>`（具体类型）而不是 `Flow<AppExtensionStatus>`（父类型），导致 `flatMapLatest` 的两个分支类型不一致。

**必须**写成 `flowOf<AppExtensionStatus>(AppExtensionStatus.VmNotRunning)` 显式指定泛型。

---

## 踩坑记录

| 问题 | 原因 | 解决 |
|------|------|------|
| Docker 报 `exec format error` | x86_64 运行 aarch64 容器 | `docker run --rm --privileged multiarch/qemu-user-static --reset -p yes` |
| 修改白名单后 initrd 仍无 qmi_wwan.ko | Docker 缓存 + QMI 是内置编译不需要 .ko | `--no-cache` 重建；验证用 `zcat vmlinuz-virt \| strings \| grep qmi_wwan` |
| `gradlew.bat` 报 `JAVA_HOME is not set` | 新 shell 不继承 `setx` 设置的环境变量 | 在同一 cmd 会话中 `set "JAVA_HOME=..."` 后再执行 gradlew |
| 下载的 keymaps 文件 git 显示 modified | Windows 行尾符 LF→CRLF 转换 | `git checkout -- app/src/main/assets/qemu/keymaps/` |
| AGP 自动下载了不同版本的 NDK | 项目未指定 ndkVersion | 正常行为，不影响构建 |
| `podroid-assets/podroid-assets/` 嵌套目录 | tar 打包时包含了一层目录 | 解压后注意路径层级 |

---

## 快速重建清单

当用户说"帮我重新构建 APK"时，按这个顺序操作：

```
[ ] 1. R0: docker build --target packer     → vmlinuz-virt + initrd.img
[ ] 2. R0: docker build -f Dockerfile.rootfs → alpine-rootfs.squashfs
[ ] 3. R0: docker build --target final       → 4 个 .so + ROM + keymaps
[ ] 4. R0: tar -czf 打包 /tmp/podroid-assets/
[ ] 5. 本地: 用户下载解压
[ ] 6. 本地: 放置产物到 Podroid 仓库对应目录
[ ] 7. 本地: set JAVA_HOME && gradlew.bat assembleDebug
[ ] 8. 验证: app-debug.apk 存在
```

如果用户只想修改 App 代码（不改内核/rootfs/QEMU），可以跳过步骤 1-5，直接从步骤 6 开始（复用已有产物）。

---

## 关键路径速查

| 项目 | 路径 |
|------|------|
| 本地 Podroid 仓库 | `C:\Users\aunsn\OneDrive\Desktop\tmp\Podroid` |
| R0 Podroid 仓库 | `/tmp/Podroid` |
| R0 产物目录 | `/tmp/podroid-assets/` |
| R0 产物压缩包 | `/tmp/podroid-assets.tar.gz` |
| Android SDK | `C:\Users\aunsn\AppData\Local\Android\Sdk` |
| JDK 17 | `C:\jdk17\jdk-17.0.20.1+1` |
| APK 输出 | `Podroid\app\build\outputs\apk\debug\app-debug.apk` |
| GitHub 仓库 | `github.com/voorz/Podroid` |
| local.properties | `Podroid\local.properties`（内容：`sdk.dir=C:\\Users\\aunsn\\AppData\\Local\\Android\\Sdk`）|
