# VNotif

把 KDE 桌面（Wayland）的通知**按应用**转发到安卓手机：每个桌面应用在手机上聚合成一条通知
（InboxStyle 摘要，来新消息原地更新，展开可看最近 5 条），点击直接拉起你选定的手机 App。

- **采集**：读会话总线上的 `org.freedesktop.Notifications.Notify`，不替换你现有的通知守护进程
- **投递**：手机单向拉一条 NDJSON 长连接（PC 不需要知道手机 IP，手机换网络无需改服务端）
- **映射**：在手机端手动选目标 App，PC 只给一个「建议包名」
- **端点**：可在 App 内配置多条（局域网 + 公网），按顺序尝试、自动回退

```
KDE 应用 ─▶ 通知守护进程 ─▶ busctl monitor ─▶ vnotif daemon (Python) ─▶ NDJSON ─▶ 手机 App
```

## 目录结构

| 路径 | 说明 |
|---|---|
| `pc/vnotif/` | PC 端守护进程（Python 标准库 + aiohttp） |
| `pc/tests/` | 解析与归并逻辑单测（样本取自真实抓包） |
| `packaging/vnotif.service` | systemd --user 单元 |
| `packaging/publish-apk.sh` | 把构建好的 APK 归档到 `dist/` 并生成 sha256 |
| `android/` | 手机端 App（纯 Java，无 AndroidX，minSdk 26 / targetSdk 36） |

## PC 端

### 依赖

Python ≥ 3.11、`aiohttp`、`busctl`（systemd 自带）。

### 部署

```bash
cd <仓库>/pc
python3 -m vnotif token                 # 首次运行会生成 ~/.config/vnotif/config.toml（含随机 token）
cp <仓库>/packaging/vnotif.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now vnotif
journalctl --user -u vnotif -f          # 看日志
```

### 命令

```bash
python3 -m vnotif token                 # 打印 token、局域网拉流地址
python3 -m vnotif tail                  # 终端里实时看转发了什么（--json 看原始消息）
python3 -m vnotif discover -t 60        # 采样 60 秒，列出出现过的应用 / desktop-entry / 可执行路径
python3 -m vnotif test-send             # 发一条测试通知
python3 -m vnotif selftest              # 端到端自检（拉流 + 发通知 + 断言），退出码 0 即链路通
python3 -m unittest discover -s tests   # 跑单测
```

### 配置 `~/.config/vnotif/config.toml`

```toml
[server]
host = "0.0.0.0"     # 局域网直连必须是 0.0.0.0
port = 8765
token = "…"          # 手机端要填同一个
tls_cert = ""        # 可选：PC 侧自建 TLS，走隧道时留空
tls_key = ""

[filter]
apps = ["kscreen", "fcitx5"]   # 黑名单：应用名或 desktop-entry，可省略 .desktop
body_regex = ["^音量"]          # 标题/正文命中正则即丢弃
```

`[filter]` 段 2 秒内自动重载；`[server]` 段改动要 `systemctl --user restart vnotif`。
**换 token 会立刻踢掉已连接的手机**。

## 手机端

### 构建

```bash
cd <仓库>/android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

归档并生成 sha256（在仓库根跑，产物落到 `dist/`，文件名取源码里的 `BUILD_TAG`）：

```bash
cd <仓库> && ./packaging/publish-apk.sh
```

不接 USB 就把 APK 拷进手机安装（需允许安装未知来源），或自己用任意 HTTP 服务分发 `dist/`。

### 安装与授权

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

装好后：

1. 授予**通知权限**（Android 13+ 会弹窗）
2. 允许**忽略电池优化**
3. 国产 ROM（如 ColorOS）：应用设置里允许**后台运行**、**自启动**；最近任务里**锁定**该卡片；关闭智能省电
4. 要用前台抑制的话，再授予**使用情况访问**

第 3 步做不到会表现为「过一阵就收不到通知」。

### 端点

首页端点列表默认预置一条局域网地址 `http://<PC 局域网 IP>:8765`，token 填 `python3 -m vnotif token` 打印的值。
公网地址作为第二条自己加（同一 token，需要的话勾「允许自签证书」）。

每条端点单独保存 token 与自签证书开关；连接时**按列表顺序**尝试，第一个连通的就用它，断线走指数退避
（1s→60s）重连。

### 映射与前台抑制

映射界面列出 PC 推来的桌面应用（含出现次数）。点条目 → 从已安装应用里选目标 App（可搜索，也可手输包名）。
长按可清除。

优先级：**手机端手选 > PC 给的建议包名 > 未映射时跳应用商店搜索页**。

**前台抑制**：某个映射应用（按上面的优先级解析出的包）在前台时，VNotif 会

1. 立刻清掉它在通知栏里的整组通知（子通知 + 摘要），
2. 丢弃它在后台到达的新通知（PC 仍会转发，手机端直接不发通知）。

与「怎么打开的」无关——点通知进来、从桌面图标手动进来都算，依赖「使用情况访问」权限；没这个权限就完全不抑制。

代价：抑制期间到达的通知是**丢弃**而不是静默保留，不会补进组里。

### 日志

App 内「日志」是二级界面，最新的在最上面，最多留 200 条，可一键清空。

## 公网访问（可选）

PC 端监听 `0.0.0.0:8765` 后，用任意隧道 / 反代暴露出去即可，例如 nginx：

```nginx
location /vnotif/ {
    proxy_pass http://127.0.0.1:8765/;
    proxy_buffering off;              # 长连接必须关缓冲
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    proxy_set_header Connection "";
    # 不要加 auth_basic：App 只带 Bearer token
}
```

改完 `nginx -t && nginx -s reload`。手机端点填 `https://<域名>/vnotif`，token 与局域网端点相同。

也可以直接开一条隧道指向 `127.0.0.1:8765`，手机端点填 `https://<域名>:<端口>`，PC 侧不用改。
隧道只能给 TCP 时，在 `config.toml` 里填 `tls_cert`/`tls_key` 自签证书，手机端点勾「允许自签证书」。

自测（不碰手机就能验）：

```bash
T=$(python3 -m vnotif token | grep -oP 'token\s*:\s*\K\S+')
curl -sk "https://<域名>/vnotif/healthz?token=$T"        # 期望 {"ok": true, ...}
curl -skN -m 5 "https://<域名>/vnotif/stream?token=$T"   # 期望立刻吐出 apps 行
```

## 排障

| 现象 | 先查什么 |
|---|---|
| 手机一直未连接 | PC 上 `curl -s 'http://127.0.0.1:8765/healthz?token=<token>'`；确认手机与 PC 同网段；手机别开 VPN（会抢走内网路由） |
| 连上了但收不到 | `python3 -m vnotif test-send`，同时看 `journalctl --user -u vnotif -f` 有没有「转发」；没有则检查黑名单 |
| 过一阵就收不到 | ROM 后台限制，按上面安装步骤第 3 步做白名单 |
| 点击通知不打开目标 App | 到映射界面选一次；确认目标 App 在手机上确实装了（未装会跳商店搜索） |
| 想单独看某条消息 | 聚合通知默认折叠，下拉（或点右侧箭头）展开即可看到该应用最近 5 条；超过 5 条只显示计数 |
| 通知不弹 / 不响 | 系统设置 → 通知 → VNotif 里有三个渠道（低 / 默认 / 高优先级），可分别设置提醒方式；聚合与渠道无关 |
| 在映射应用里还弹消息 | 前台抑制没生效：确认「使用情况访问」已授予 |
| 收不到某类通知 | 有些应用不走 D-Bus 通知（如浏览器扩展自定义弹窗、游戏内 HUD），本工具只能看到标准桌面通知 |
| 端口冲突 | 改 `config.toml` 的 `port`，手机端点同步改 |

## 已知限制（v1）

- **不做**「桌面端关掉通知 → 手机上同步消失」（需要守护进程分配的通知 id，且手机通知本就该独立留存）
- 不转发桌面通知里的操作按钮与图片；手机上用目标 App 自身的图标
- 仅单设备、无通知历史持久化
- 只捕获标准 D-Bus 桌面通知

## 协议（PC → 手机，NDJSON）

手机：`GET {base}/stream?token=…`（或 `Authorization: Bearer …`），响应为不会结束的 `application/x-ndjson`，
每行一个 JSON：

```json
{"v":1,"op":"notify","key":":1.91:1172","app":"Element","app_id":"Element","pkg_hint":"im.vector.app","title":"标题","body":"正文","urgency":1,"ts":1790699532.127}
{"v":1,"op":"update","key":":1.91:1172","title":"新标题","body":"新正文"}
{"v":1,"op":"dismiss","key":":1.91:1172"}
{"v":1,"op":"apps","list":[{"app":"Element","app_id":"Element","pkg_hint":"im.vector.app","seen":42,"last":1790699532}]}
{"v":1,"op":"ping","ts":1790699535.0}
```

- `key` = `<D-Bus sender>:<cookie>`；同一通知被更新时复用 key 并置 `op=update`
- 连接后服务端先推一条 `apps`；此后每 20 秒一条 `ping`（客户端读超时 45s）
- 另有 `GET /healthz?token=…` 与 `POST /report`（手机端回传连接状态与前台包名）
