# HypixelTranslate-Proxy

> Minecraft Java 1.8.9（协议 47）本地代理 · 聊天双向翻译 · Tab 挂统计

![Java](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)
![Minecraft](https://img.shields.io/badge/Minecraft-1.8.9%20(protocol%2047)-62B47A)
![License](https://img.shields.io/badge/License-MIT-blue)

在本地把客户端连接**终结**掉（客户端这一侧全程明文），再用你自己的微软账号向 Hypixel 发起一次真实登录。明文因此出现在本机，聊天才能被读到、翻译、改包。

```
你的客户端 ──▶ 本代理 127.0.0.1:25565 ──▶ 上游（加速IP / mc.hypixel.net）──▶ 服务器
                ↑ 明文，可读可改              ↑ 加密，代理自己的登录会话
```

只做「读聊天 + 翻译 + 改显示名」，没有任何自动发包、自动行走之类的功能。

## 它和「转发型加速IP」的区别

|  | 加速IP | 本代理 |
|---|---|---|
| 链路 | L4 哑管道，只改握手里的域名，其余原样转发 | 本地终结客户端连接，再独立登录上游 |
| 能看到聊天 | 不能，全链路密文 | 能，明文就产生在本机 |
| 代价 | 无 | 一个微软账号同时只能顶一个会话；本机要跑 Java |
| 用途 | 单纯改善线路 | 翻译 / 改包 / 挂统计 |

## 功能

- **收消息翻译**：服务器消息翻成 `translate.targetLanguage`（默认中文 `zh-CN`，可改）；译文固定在原文下一行（`└ …`），按原文顺序排队，不互相穿插。
- **发消息翻译**：你打的文字翻成 `outgoingTargetLanguage`（默认英文 `en`，可改）再发出去；`/pc`、`/msg` 这类聊天命令只翻正文，命令和玩家名原样。
- **不浪费 token**：黑话（`gg`/`lol`/`ez`…）、短句、重复刷屏、`[NPC]` 对话、非玩家聊天默认跳过；已是目标语言的不翻。
- **Tab 挂统计**：玩家名后加 FKDR 等数据，不影响头顶名牌。数据源支持 Hypixel 官方 API / 第三方统计站 / auto 降级。
- **服务器列表 MOTD**：可自定义文字、颜色码、显示人数。
- **断线联动**：客户端断开立刻断上游，上游踢人立刻断本地，不残留挂机。

## 快速开始

要求：**Java 17 或更高**。

```bash
java -jar HypixelTranslate-Proxy.jar
```

从源码构建：

```bash
git clone <repo-url>
cd HypixelTranslate-Proxy
mvn -DskipTests package
java -jar target/HypixelTranslate-Proxy.jar
```

产物是包含全部依赖的可执行 fat jar（`maven-shade-plugin`）。

### 第一次运行

1. 自动生成 `config.json`
2. 打印微软设备码，浏览器打开 <https://www.microsoft.com/link> 输入代码完成登录
3. 令牌存到 `auth.json`，之后自动续期

然后编辑 `config.json`：至少填 `translate.apiKey`（翻译接口密钥），需要 Tab 统计再填 `tab.hypixelApiKey`。

### 游戏内连接

多人游戏 → 直接连接 → `127.0.0.1:25565`（端口由 `listenPort` 决定）。

## 接加速IP

只改上游这两项即可：

```json
{
  "upstreamHost": "1.2.3.4",
  "upstreamPort": 16448
}
```

握手域名保持默认的 `mc.hypixel.net` —— Hypixel 按握手里的域名路由，配置文件里不写就是它，别改成加速商的域名。

## 配置

`config.json` 首次运行时由 jar 内置模板生成（就是下表这些字段）。没写的字段代码里有默认值，不写也能跑。

### 基础

| 键 | 默认 | 说明 |
|---|---|---|
| `listenPort` | `25565` | 游戏内直连 `127.0.0.1:25565`；默认只监听本机 |
| `upstreamHost` / `upstreamPort` | `mc.hypixel.net` / `25565` | 上游地址；用加速器时换成加速器给的 IP 和端口 |
| `motd.text` | 见配置 | 列表文字，支持 `§` 颜色码与 `\n` 换行 |
| `motd.online` / `motd.max` | `1` / `1` | **只影响显示**，实际仍是一账号一会话 |

### 翻译 `translate`

| 键 | 默认 | 说明 |
|---|---|---|
| `apiKey` | 空 | 翻译接口密钥。为空时不翻译，也不会拦截你发出去的中文 |
| `targetLanguage` | `zh-CN` | 收到的消息翻成什么语言，`en` / `ja` 等随便填 |
| `model` | `deepseek-flash` | 模型名；接口按 `{baseUrl}/chat/completions` 调用，`baseUrl` 默认 `https://api.deepseek.com` |
| `appendWaitMs` | `250` | 原文最多压多久等译文（毫秒），超时先显示原文 |
| `translateOutgoing` | `true` | 把中文发出去前翻成外语 |
| `outgoingTargetLanguage` | `en` | 发出去翻成什么语言 |
| `onlyPlayerChat` | `true` | 只翻能认出「名字: 内容」的玩家聊天 |
| `skipShortMessages` | `10` | 去掉名字后字母数 ≤ 该值的短句不翻，`0` 关闭 |
| `dedupSeconds` | `60` | 同一句多少秒内重复出现不再翻，`0` 关闭 |
| `skipPrefixes` | `["[NPC]"]` | 这些开头的消息不翻；Tab 里这类 NPC 也不查统计 |
| `skipWords` | gg/lol/ez… | 整条消息只由这些词组成就不翻；清空 `[]` 关闭 |

> 收发两个方向的语言分开配：`targetLanguage` 管收进来的消息，`outgoingTargetLanguage` 管发出去的消息。

### Tab 统计 `tab`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 总开关 |
| `hypixelApiKey` | 空 | Hypixel 官方 key；`official` 模式下留空则不查 |
| `dataSource` | `auto` | `official` / `thirdparty` / `auto` |
| `thirdPartyBaseUrl` | `https://api.khadow.lol` | 第三方统计站，实际请求 `POST /stats` |
| `stat` | `bedwars_fkdr` | 也支持 `bedwars_wins` / `bedwars_final_kills` / `bedwars_winstreak` / `skywars_kdr` |
| `label` / `separator` / `valueColor` | `FKDR` / `" §7\| "` / `§b` | 显示样式 |

### 数据源怎么选

- `official`：Hypixel 官方 `/player` 接口，需要 key，每天 300 次；被 429 后冷却期内不再查。
- `thirdparty`：按**玩家名**查询，不消耗 Hypixel 额度，但只保证 FKDR，稳定性不由本项目控制。
- `auto`（默认）：先查官方；没 key、429、查不到时切第三方；两边都查不到才算「查不到」。

**查不到不等于程序坏了**：nick 玩家、新号、关闭了 API 统计、第三方没覆盖，都会查不到。

## 工作原理

### 客户端侧

代理**故意不向客户端发 EncryptionRequest**，所以客户端到代理这一段不需要加密，代理能直接读包。

### 上游登录（完整 1.8.9 流程）

1. `Handshake(0x00)` → `LoginStart(0x00)`，名字用代理账号名，握手域名用默认的 `mc.hypixel.net`
2. `EncryptionRequest(0x01)`：生成 16 字节 shared secret，用服务端 RSA 公钥加密 secret 与 verifyToken
3. 计算 `sha1(serverId + secret + publicKey)`，调用 sessionserver 的 join 接口（阻塞 HTTP 丢到独立线程池，不占用 Netty eventLoop）
4. 回 `EncryptionResponse(0x01)`，启用 **AES/CFB8**（key = IV = shared secret），加解密在帧层之前
5. `SetCompression(0x03)`：后续包走 `[未压缩长度][zlib 数据]`，未压缩包写长度 `0`
6. `LoginSuccess(0x02)` 后两端进入 PLAY，变成纯中继

### 聊天

- 下行 `0x02`：解析 JSON 组件（含 `translate` 键）拍平成 `Steve: 内容`，过滤后送翻译接口；`ChatOutbox` 按 seq 出队，先发原包，再发 `└ 译文`。
- 译文行固定用 `CHAT` 位置：1.8.9 的 `SYSTEM` 位置是「固定行号」通道，新消息会顶掉上一条同位置的，原文和译文会互相吃掉。
- 上行 `0x01`：只有含中日韩文字才翻，且要求 `apiKey` 非空；长度超过 100 字符或 `!` 开头不处理。
- 聊天命令白名单：`pc ac gc sc shout p party g guild o officers msg m w tell whisper r reply me channel c`。只翻第一个汉字之后的正文，命令与玩家名原样；其它命令（`/spawn`、`/gift`）完全不碰。
- 兜底：翻译超时、失败或队列满 → 原消息照发，不丢消息。

### Tab

1.8 的玩家列表包 `0x38` 有独立的 `displayName` 字段（也可用 `action=3` 单独更新它）。Tab 一行 = 队伍前缀 + displayName + 队伍后缀，而**头顶名牌不使用 displayName**，所以只改 `displayName` 就能在 Tab 名字后加字而不动名牌。

代价是：一旦下发了 displayName，客户端就不再套 scoreboard 队伍 —— 名字会变纯白、队伍前缀丢失。所以代理同时镜像了 `0x3E` 队伍包（前缀 / 后缀 / 颜色），挂统计时自己拼回去；服务器给的 displayName 则原样保留，一个字不改。

## 隐私与安全

- **聊天内容会发给翻译接口**：翻译接口地址指向谁，聊天就发给谁。
- **玩家名会发给第三方统计站**：`dataSource` 为 `auto` / `thirdparty` 时，Tab 里玩家的用户名会 POST 到 `thirdPartyBaseUrl`。
- **`auth.json` 是明文 refresh token**：删掉即退出登录；不要提交、不要分享。
- **默认只监听 `127.0.0.1`**：不要把监听地址改成 `0.0.0.0`，那等于把账号开放给同网段所有人。
- 代理不落盘记录聊天，只在控制台打印翻译结果。
- 启动日志会打印 Hypixel key 的前 6 位用于排查，完整 key 不会输出。

## 已知限制

1. **一个实例一个账号一个会话**，同一账号开第二个会被服务器顶掉。
2. **只支持 1.8.9（协议 47）**，不做版本翻译。
3. **Tab 队伍信息晚于玩家列表时，首帧会丢队伍前缀/队色**：队伍包到达后没有触发重绘。
4. **翻译吞吐有限**：4 线程 + 单条超时，刷屏时会丢译文（但不阻塞聊天）。
5. **走公共加速IP 时出口 IP 是共享的**，服务器侧风控可能受影响。
6. **Tab 统计缓存只在当前会话内**：重连后会用同一份额度重新查一遍。
7. **无鉴权的本地接口（Ollama 等）只能用于收消息翻译**：`apiKey` 为空时代理不会拦截要发出去的中文，填任意非空字符串可绕过。
8. **未在真实 Hypixel 上验证**的部分：真实账号登录与 sessionserver、加速IP 实际行为、真实 Tab 结构下的 displayName 效果、长时间挂机稳定性。

## 常见问题

**Tab 上没数字？** 确认 `tab.enabled=true` 且 `hypixelApiKey` 不为空；再看是不是 nick 玩家 / 关闭了 API 统计 / 当天额度用完。

**名字变白、队伍称号没了？** 见上文 Tab 一节：displayName 会覆盖队伍渲染，代理会尽量补回前缀，但队伍包晚到时补不回来。

**译文有时晚一行？** 原文最多等 `appendWaitMs`，超时先放原文，译文后到会单独补一行。

**能给朋友用吗？** 不能：一个微软账号同一时间只能顶一个会话。

**支持 1.9 以上吗？** 不支持，协议号写死 47。

## 免责声明

- 本项目与 Mojang、Microsoft、Hypixel 没有任何关系。
- 仅用于学习 Minecraft 网络协议与个人自用。使用前请自行确认目标服务器的服务条款；**使用非官方客户端/代理可能被判定违规并导致封号**，风险自负。
- 登录走微软设备码流程，属于非官方客户端接入方式，随时可能失效。
- 请勿用本项目做自动发包、自动行走、刷分等自动化行为。

## 目录结构

```
src/main/java/com/mcproxy/
├── Main.java                入口
├── Config.java              配置模型（默认值 + 缺失字段兜底）
├── ProxyServer.java         监听客户端
├── Session.java             一次会话：两条连接绑一条命
├── auth/AuthService.java    微软设备码登录 + sessionserver join
├── proto/                   VarInt / 帧 / 压缩 / AES / 两侧 handler / Tab 探针
├── chat/                    组件拍平、模型客户端、翻译队列、聊天出口队列
└── tab/                     Tab 统计注入、队伍前缀镜像、诊断日志
```

## 联系

- 邮箱：3844005667@qq.com
- Issue / PR 也可以直接在仓库里提

## License


[MIT](LICENSE)。协议细节参考 [wiki.vg](https://wiki.vg/) 与 Minecraft 1.8.9 客户端实现。
