# VNotif

把 KDE 桌面（Wayland）的通知**按应用**转发到安卓手机：**每个桌面应用在手机上聚合成一条通知**（InboxStyle 摘要，来新消息原地更新，展开可看最近 5 条），点击直接拉起你选定的手机 App。

- 采集：读会话总线上的 `org.freedesktop.Notifications.Notify`，不替换你现有的通知守护进程（本机是 noctalia）
- 投递：手机单向拉一条 NDJSON 长连接（PC 不需要知道手机 IP，手机换网络无需改服务端）
- 映射：**在手机端手动选**，从已安装应用列表里挑，PC 只给一个"建议包名"
- 外网：**FRP 地址由你在 App 内手填**，支持多条端点与自动回退

```
KDE 应用 ─▶ noctalia ─▶ busctl monitor ─▶ vnotif daemon(Python) ─▶ NDJSON ─▶ 手机 App
                                             过滤/映射建议                    分组/点击跳转
```

## 验证状态（2026-09-30）

已实测跑通（PC 端）：

- 采集 → 过滤 → 建议映射 → NDJSON 推送全链路：`python3 -m vnotif selftest` 退出码 0
- `replaces_id` 归并：同一通知的更新以 `op=update` 复用同一个 `key`（靠守护进程回执取到通知 id）
- 黑名单拦截生效；错误 token 返回 401
- 流式推送无缓冲：首行 0.01s；心跳严格 20s 一次（客户端读超时 45s）
- 大消息不再撑死采集端：`tools/send_big_notification.py` 发 200KB 通知后，两个监听子进程仍在、通知仍被转发
- 手机上挂着长连接时 `systemctl --user restart` 秒级完成（不再等 aiohttp shutdown 超时被 SIGKILL）
- `systemctl --user` 常驻 + 开机自启；PC 端单测 16 项全绿

尚未验证（需要真机介入）：

- 手机 App 的运行时行为：ColorOS 后台常驻、通知分组的实际观感、点击跳转的落点、自签证书放行、映射选择器能否列出你手机上的应用
- FRP 隧道下的端到端连通（隧道需你在 SakuraFrp 面板新建）
- 手机 App 只做了静态核验（协议字段、`<queries>`、`specialUse` 前台服务、通知 id 复用、映射优先级），未做真机联调

## 目录

| 路径 | 说明 |
|---|---|
| `pc/vnotif/` | PC 端守护进程（Python 标准库 + aiohttp） |
| `pc/tests/` | 解析与归并逻辑单测（样本取自本机真实抓包） |
| `packaging/vnotif.service` | systemd --user 单元 |
| `android/` | 手机端 App（纯 Java，无 AndroidX，minSdk 26 / targetSdk 36） |

## PC 端

### 依赖

Python ≥ 3.11（本机 3.14）、`aiohttp`、`busctl`（systemd 自带）。都不需要额外安装。

### 部署

```bash
cd ~/Dev/VNotif/pc
python3 -m vnotif token                 # 首次运行会生成 ~/.config/vnotif/config.toml（含随机 token）
cp ~/Dev/VNotif/packaging/vnotif.service ~/.config/systemd/user/
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
python3 tools/send_big_notification.py  # 回归：发一条 200KB 的通知（历史上这条能撑死采集端）
python3 -m unittest discover -s tests   # 跑单测
```

### 配置 `~/.config/vnotif/config.toml`

```toml
[server]
host = "0.0.0.0"     # 局域网直连必须是 0.0.0.0
port = 8765
token = "…"          # 手机端要填同一个
tls_cert = ""        # 走 SakuraFrp 的 HTTPS 隧道时留空；自签 TLS 时填证书路径
tls_key = ""

[filter]
apps = ["kscreen", "fcitx5"]   # 黑名单：应用名或 desktop-entry，可省略 .desktop
body_regex = ["^音量"]          # 标题/正文命中正则即丢弃
```

改完 `systemctl --user restart vnotif`。**换 token 会立刻踢掉已连接的手机**。

## 手机端

### 构建

```bash
cd ~/Dev/VNotif/android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

依赖走 `~/.gradle/gradle.properties` 里配好的本机代理；AGP 9.2.0 与 Gradle 9.4.1 已在本机缓存。

### 下载链接（不接 USB 时用）

```bash
./packaging/publish-apk.sh      # 把 app-debug.apk 发到 dist/，文件名取源码里的 BUILD_TAG
```

之后手机浏览器打开（`vnotif.apk` 永远指向最新版，出 r15 也是同一个链接）：

| 场景 | 链接 |
|---|---|
| 手机在家 WiFi | `http://192.168.1.215:8080/apk/vnotif.apk` |
| 手机在家 WiFi（HTTPS） | `https://192.168.1.215:8443/apk/vnotif.apk` |
| 手机用流量 / 在外面 | `https://frp-hat.com:37070/apk/vnotif.apk`（自签证书，浏览器提示不受信 → 继续） |
| 挑历史版本 | 上面三条把结尾换成 `/apk/`，看目录列表 |

nginx 里是三个 `location /apk/`（8080 / 8443 / 8446），都 alias 到 `~/Dev/VNotif/dist/`。
公网那条没加 `auth_basic`（手机浏览器直接点开就能下）；APK 里不含 token，风险可接受。

### 界面

主界面是卡片式的：**连接**（状态 / 端点 / 地址 / 最后消息 / 最后错误 + 启停）→ **权限与保活**（通知权限 /
忽略电池优化 / 使用情况访问，点一行直接跳系统设置；开机自启）→ **端点**（按顺序列表，可上下移、单条测试、
按顺序测试）→ **映射** → **日志**（默认收起）。

主题：`AppTheme` 三个变体只有 parent 不同（浅色 `DeviceDefault.Light` / 深色 `DeviceDefault` /
API 29+ `DayNight`），窗口色一律引用语义色。语义色（`vnotif_surface`、`vnotif_on_surface_variant`、
`vnotif_primary_container`…）定义在 `res/values/colors.xml` + `res/values-night/colors.xml`；
**交互色不自己定**，用框架主题的 `colorAccent`——Android 12+ 上它就是系统 Monet 取色结果。
工程仍然不依赖 AndroidX / Material 库（拿不到 `colorSurface` 这类库属性，所以自己定义同名 token）。

已删掉的功能（都是排查期的一次性实验，结论已固化进代码）：

- **左侧图标四档切换**（资源 / 彩色位图 / 单色剪影 / 铃铛）→ 只用 launcher 资源图标，取不到才回退铃铛
- **右侧大图标开关** → 固定关闭（ColorOS 上大图标会顶掉系统时间戳，而用户要的是「图标 + 时间」）
- **root 深度诊断**（`su -c dumpsys notification` 回传）→ 图标问题已解决，不再需要 root
- **端点"上次成功优先"** → 改为严格按界面顺序，顺序即优先级

### 安装与授权

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

不接 USB 就把 APK 拷进手机安装（需允许安装未知来源）。装好后：

1. 授予**通知权限**（Android 13+ 会弹窗）
2. 允许**忽略电池优化**
3. 一加 13 / ColorOS：设置 → 应用 → VNotif → 允许**后台运行**、**自启动**；最近任务里**锁定**该卡片；关闭对该应用的智能省电
   （做不到这一步会表现为"过一阵就收不到通知"）

### 填端点

App 首页端点列表，默认预置一条局域网地址 `http://<PC 局域网 IP>:8765`，把 **token** 填成 `python3 -m vnotif token` 打印的值即可。
FRP 公网地址作为**第二条端点**自己加，例如 `https://xxx.natfrp.com`（同一 token）。

工作方式：App 为每条端点单独保存 token 与"允许自签证书"开关，连接时按顺序尝试，断线走指数退避（1s→60s）重连。

### 映射（决定点击通知打开哪个 App）

映射界面列出 PC 推来的桌面应用（含出现次数）。点条目 → 从已安装应用里选目标 App（可搜索，也可手输包名）。长按可清除。

优先级：**手机端手选 > PC 给的建议包名 > 未映射时跳应用商店搜索页**。

**前台抑制**：只要某个映射应用（按上面的优先级解析出的那个包）在前台，VNotif 就会

1. 立刻清掉它在通知栏里的整组通知（子通知 + 摘要），
2. 丢弃它在后台到达的新通知（PC 仍会转发，手机端直接不发通知）。

与"怎么打开的"无关——点通知进来、从桌面图标手动进来，都算。所以手机端需要有「使用情况访问」权限
（主界面按钮显示"已授予"）；没这个权限就完全不抑制，也不会报错。

代价说清楚：抑制期间到达的通知是**丢弃**而不是静默保留，不会补进组里。想让它们静默留在通知栏
（不弹横幅、不响）而不是丢掉，需要改 `NotifRenderer` 的抑制分支，目前没做。

## 外网（SakuraFrp）

PC 端只需监听 `0.0.0.0:8765`。**本机走的是"搭 dsh 那条隧道"的方案**：natfrp 免费套餐只有 2 条隧道，
dsh 与 SSH 已经共用一条（`dsh-frp-demux` 按首字节分流），没有第三条留给 VNotif，所以按路径挂在同一条上。

```
公网 https://frp-hat.com:37070 → TCP 隧道 → 127.0.0.1:8081 (dsh-frp-demux)
   ├─ "SSH-" 开头 → sshd:22
   └─ 其余(TLS)   → nginx:8446 ┬ /        → dsh:8099（Basic 认证）
                               └ /vnotif/ → VNotif:8765（token 认证，不加 Basic）
```

手机端点填 `https://frp-hat.com:37070/vnotif`，勾上"允许自签证书"，token 与局域网端点相同。

配置在 `~/Dev/nginx/conf.d/dsh-web.conf` 的 `listen 8446 ssl` 里（`location /vnotif/`）。
改完必须 `nginx -p ~/Dev/nginx/ -c nginx.conf -t && nginx -p ~/Dev/nginx/ -c nginx.conf -s reload`。

自测（不碰手机就能验）：

```bash
T=$(python3 -m vnotif token | grep -oP 'token\s*:\s*\K\S+')
curl -sk "https://frp-hat.com:37070/vnotif/healthz?token=$T"        # 期望 {"ok": true, ...}
curl -skN -m 5 "https://frp-hat.com:37070/vnotif/stream?token=$T"   # 期望立刻吐出 apps 行
```

两个坑：① `/vnotif/` 千万别加 `auth_basic`（App 只带 Bearer，加了就永远 401）；② `/stream` 是永不结束的
长连接，`proxy_buffering off` + `proxy_read_timeout` 大于心跳（20s）是命门。

**另一条路（还有隧道名额时）**：单独建一条隧道指向 `127.0.0.1:8765`，手机端点直接填 `https://域名:端口`，
PC 侧什么都不用改。隧道类型优先 HTTPS（隧道侧终结 TLS）；只能 TCP 时在 `config.toml` 填 `tls_cert`/`tls_key`
自签证书，手机端点勾"允许自签证书"。

注意：这台机器上 natfrp 日志里隧道频繁 `数据连接断开/EOF`，属于隧道侧抖动——首次连接失败重试一次即可，
客户端会自动重连，不必手动干预。走这条路的客户端在 PC 日志里显示为 `127.0.0.1`（frp/nginx 没透传真实 IP）。

## 排障

| 现象 | 先查什么 |
|---|---|
| 手机一直未连接 | PC 上 `curl -s 'http://127.0.0.1:8765/healthz?token=<token>'`；确认手机与 PC 同网段；手机别开 VPN（会抢走 192.168.x 路由） |
| 公网端点连不上 | 先 `curl -sk 'https://frp-hat.com:37070/vnotif/healthz?token=<token>'`：401 是 token 错；连接超时/TLS 报错是 frp 隧道抖动，重试一次即可。手机端点地址必须自己带 `https://`，否则 App 会补成 `http://` |
| 连上了但收不到 | `python3 -m vnotif test-send`，同时看 `journalctl --user -u vnotif -f` 有没有"转发"；没有则检查黑名单 |
| 服务 active 但**任何**通知都收不到 | `systemctl --user status vnotif` 里应有**两个** `busctl --user monitor` 子进程（一个 `interface=`、一个 `sender=`）。少了 `interface=` 那个就是采集中断 → `systemctl --user restart vnotif`。用 `python3 tools/send_big_notification.py` 可回归验证（带图标的大通知曾能把它撑死，已修） |
| 点击通知不打开目标 App | 到映射界面选一次；确认目标 App 在手机上确实装了（未装会跳商店搜索） |
| 想单独看某条消息 | 聚合通知默认折叠，下拉（或点右侧箭头）展开即可看到该应用最近 5 条；超过 5 条只显示计数 |
| 通知不弹 / 不响 | 系统设置 → 通知 → VNotif 里有三个渠道（低 / 默认 / 高优先级），可分别设置提醒方式；聚合与渠道无关 |
| 过一阵就收不到 | ColorOS 后台限制，按上面第 3 步做白名单 |
| 在映射应用里还弹消息 | 前台抑制。主界面「前台抑制权限」必须是**已授予**；PC 日志里 `手机上报 [fg]` 会打出抑制期间探到的前台包名，正常应等于该应用的包名（如 `moe.aks.matter`），若一直显示 `(空)` 或别的包名就是前台判定没认出来 |
| 打开映射应用后通知还堆着不消 | 正常行为是进前台 2 秒内自动清组。没清说明前台没认出来（同上，看 `[fg]` 日志有没有 `前台自动` 那条）；若该应用只靠 PC 的 `pkg_hint` 解析、而提示的包名跟你手机上装的不是同一个，就不会认出来 |
| 收不到某类通知 | 有些应用不走 D-Bus 通知（如浏览器扩展自定义弹窗、游戏内 HUD），本工具只能看到标准桌面通知 |
| 端口冲突 | 改 `config.toml` 的 `port`，手机端点同步改 |

## 已知限制（v1）

- **不做**"桌面端关掉通知 → 手机上同步消失"（需要守护进程分配的通知 id，且手机通知本就该独立留存）
- 不转发桌面通知里的操作按钮与图片；手机上用目标 App 自身的图标
- 仅单设备、无通知历史持久化
- 只捕获标准 D-Bus 桌面通知

## 协议（PC → 手机，NDJSON）

手机：`GET {base}/stream?token=…`（或 `Authorization: Bearer …`），响应为不会结束的 `application/x-ndjson`，每行一个 JSON：

```json
{"v":1,"op":"notify","key":":1.91:1172","app":"Element","app_id":"Element","pkg_hint":"im.vector.app","title":"标题","body":"正文","urgency":1,"ts":1790699532.127}
{"v":1,"op":"update","key":":1.91:1172","title":"新标题","body":"新正文"}
{"v":1,"op":"dismiss","key":":1.91:1172"}
{"v":1,"op":"apps","list":[{"app":"Element","app_id":"Element","pkg_hint":"im.vector.app","seen":42,"last":1790699532}]}
{"v":1,"op":"ping","ts":1790699535.0}
```

- `key` = `<D-Bus sender>:<cookie>`；同一通知被更新时复用 key 并置 `op=update`
- 连接后服务端先推一条 `apps`；此后每 20 秒一条 `ping`（客户端读超时 45s）
