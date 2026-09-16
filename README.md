# Copyparty Z Flip5

在 **Samsung Galaxy Z Flip5** 上运行上游 [copyparty](https://github.com/9001/copyparty) 局域网文件服务器，并提供 **Flex Window（封面屏幕）** 控制面板小部件。

- 嵌入方式：**Chaquopy 17** + 应用内 **Python 3.13** + PyPI **`copyparty==1.20.23`**（见 `app/build.gradle.kts`）
- ABI：仅 **`arm64-v8a`**（手机）
- 钉选版本说明：`vendor/COPYPARTY_VERSION.txt`（不提交完整 upstream 克隆）

## 功能

- 内屏设置：端口（默认 3923）、只读/读写、可选密码、SAF 共享目录、「所有文件访问权限」
- 删除/移动文件：必须关闭「只读模式」（默认开启）。非只读时 volume 权限为 `rwdm`（读/写/删/移）；只读为 `r`。
- **网卡 / 局域网地址** 多选：自动（默认，监听 `0.0.0.0` 并显示最佳 LAN IP）、显式 `0.0.0.0 — 全部 IPv4`、或勾选一个/多个 `iface — ip`（多 IP 以逗号传给 copyparty `-i`）
- 前台服务 + 通知栏停止；主界面二维码
- 封面小部件：状态、启停、URL/端口、刷新、打开主屏设置（`display="sub_screen"`）

## 构建

需要 JDK 17+、Android SDK 34、本机 **Python 3.13**（与 Chaquopy 版本一致，供打包期 pip/pyc）。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # 按本机调整
export ANDROID_HOME=/path/to/android-sdk
# local.properties: sdk.dir=...

cd /workspace/copyparty-zflip5   # 或你的克隆路径
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（已 gitignore，不会进仓库）。

侧载：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 使用简要

1. 打开应用 → 通知权限 → **授予所有文件访问权限** → 选择共享目录  
2. （可选）点「选择监听地址…」勾选网卡 / `0.0.0.0`，保存后启动  
3. 同一 Wi‑Fi 浏览器打开界面上的 `http://<IP>:3923/`（有密码时用户为 `share`）  
4. 封面小部件：**设置 → 封面屏幕 → 小部件** → 启用「文件服务器控制面板」

## 架构

```
MainActivity / CoverWidget
        │
        ▼
FileServerService (FGS)
        │
        ▼
CopypartyController ──► party_bridge.py ──► copyparty.__main__.main
```

自动网卡排序：ConnectivityManager Wi‑Fi/Ethernet → `wlan*`/`eth*` → `192.168.*` → `10.*` → `172.16–31.*`；排除 docker/veth/br-/tun 等；`172.19.*` 大幅降权。

## 限制

- 需要「所有文件访问权限」才能把 SAF 目录解析成真实路径给 copyparty  
- 默认 `--no-thumb`（未捆绑 FFmpeg/Pillow）  
- 仅建议局域网使用；勿端口映射到公网  
- 封面小部件需 One UI / Z Flip5 类设备  

## 许可证

本仓库示例代码按需使用。copyparty / Chaquopy 17 / NanoHTTPD 历史依赖等遵循各自上游许可证（当前运行时为 Chaquopy + PyPI copyparty）。
