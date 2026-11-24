<div align="center">

# HypixelTranslate-Proxy

**Minecraft Java 1.8.9（协议 47）本地代理 · 聊天双向翻译 · Tab 挂统计**

![Java](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)
![Minecraft](https://img.shields.io/badge/Minecraft-1.8.9%20(protocol%2047)-62B47A)
![License](https://img.shields.io/badge/License-MIT-blue)

[原理](#工作原理) · [快速开始](#快速开始) · [配置](#配置) · [隐私](#隐私与安全) · [已知限制](#已知限制与未验证项)

</div>

---

在本地把客户端连接**终结**掉（客户端这一侧全程明文），再用你自己的微软账号向 Hypixel 发起一次真实登录。明文因此出现在本机，聊天才能被读到、翻译、改包。

```
你的客户端 ──▶ 本代理 127.0.0.1:25565 ──▶ 上游（加速IP / mc.hypixel.net）──▶ 服务器
                ↑ 明文，可读可改              ↑ 加密，代理自己的登录会话
```

> 只做“读聊天 + 翻译 + 改显示名”，不含任何自动发包、自动行走之类的自动化功能。

## 它和「转发型加速IP」的区别

|  | 加速IP | 本代理 |
|---|---|---|
| 链路 | L4 哑管道，只改握手里的域名，其余原样转发 | 本地终结客户端连接，再独立登录上游 |
| 能看到聊天 | ❌ 密文，一个字节都读不到 | ✅ 明文就产生在本机 |
| 代价 | 无 | 一个微软账号只能顶一个会话；本机要跑 Java |
| 用途 | 纯粹改善线路 | 翻译 / 改包 / 挂统计 |

## 功能

- **收消息翻译**：译文固定在原文下一行（`└ …`），按原文顺序排队，不会互相穿插。
- **发消息翻译**：你打的中文自动翻成外语再发出去（默认英文）；`/pc`、`/msg` 这类聊天命令只翻正文，命令和玩家名原样。
- **不浪费 token**：黑话（`gg`/`lol`/`ez`…）、短句、重复刷屏、`[NPC]` 对话、非玩家聊天默认跳过；已是中文的不翻。
- **Tab 挂统计**：名字后面加 FKDR 等数据，不影响头顶名牌。数据源支持 Hypixel 官方 API / 第三方统计站 / auto 降级。
- **服务器列表 MOTD**：可自定义文字、颜色码、显示人数。
- **断线联动**：客户端断开立刻断上游，上游踢人立刻断本地，不残留挂机、不卡黑屏。

## 快速开始

要求：**Java 17 或更高**。

### 方式一：直接运行 Release

```bash
mkdir HypixelTranslate-Proxy && cd HypixelTranslate-Proxy
cp /path/to/HypixelTranslate-Proxy.jar .
java -jar HypixelTranslate-Proxy.jar
```

### 方式二：从源码构建

```bash
git clone <your-repo-url>
cd HypixelTranslate-Proxy
mvn -DskipTests package
java -jar target/HypixelTranslate-Proxy.jar
```

产物是一个包含全部依赖的可执行 fat jar（`maven-shade-plugin`）。

### 第一次运行

1. 自动生成 `config.json`
2. 打印微软设备码，浏览器打开 <https://www.microsoft.com/link> 输入代码完成登录
3. 令牌存到 `auth.json`，之后自动续期（文件权限 600）

然后编辑 `config.json`：至少填 `translate.apiKey`（翻译用的 OpenAI 兼容接口密钥），并确认 `upstreamHost`。

### 游戏内连接

多人游戏 → 直接连接 → `127.0.0.1:25565`（或你配置的 `listenHost:listenPort`）。

## 接加速IP

```json
{
  "upstreamHost": "1.2.3.4",
  "upstreamPort": 25565,
  "handshakeHost": "mc.hypixel.net"
}
```

**`handshakeHost` 必须写 `mc.hypixel.net`**，即使 `upstreamHost` 是加速IP 的地址。Hypixel 按握手里的域名做路由，写加速域名会进不去主服。

链路变成：`客户端 → 本代理（解密/翻译） → 加速IP（哑转发） → Hypixel`。加速IP 只当线路，不影响登录。

## 配置

配置文件是带注释的 JSON（Gson 宽松模式，`//` 注释合法）。仓库不跟踪 `config.json`，避免误提交密钥。

### 基础

| 键 | 默认 | 说明 |
|---|---|---|
| `listenHost` / `listenPort` | `127.0.0.1` / `25565` | 监听地址。**改 `0.0.0.0` 等于把账号开放给同网段所有人** |
| `upstreamHost` / `upstreamPort` | `mc.hypixel.net` / `25565` | 上游地址，可以是加速IP |
| `handshakeHost` / `handshakePort` | `mc.hypixel.net` / `25565` | 握手包里写的域名，Hypixel 靠它路由 |
| `protocolVersion` | `47` | 固定 1.8.9，客户端版本不符会被明确踢出并提示 |
| `authFile` | `auth.json` | 登录令牌文件，删掉就要重新登录 |
| `idleTimeoutSeconds` | `60` | 任一侧空闲多久断开（防残留会话） |
| `openBrowserOnLogin` | `true` | 设备码登录时自动打开浏览器；无桌面环境自动降级 |
| `copyCodeToClipboardOnLogin` | `true` | 自动复制设备码（Windows `clip` / macOS `pbcopy` / Linux `xclip`、`xsel`） |
| `motd.text` | 见配置 | 列表文字，支持 `§` 颜色码和 `\n` 换行 |
| `motd.online` / `motd.max` | `1` / `1` | **只影响显示**，实际仍是一账号一会话 |

### 翻译 `translate`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 翻译总开关 |
| `targetLanguage` | `zh-CN` | 收消息翻成什么语言 |
| `baseUrl` / `apiKey` / `model` | DeepSeek / 空 / `deepseek-flash` | 任意 OpenAI 兼容 `/chat/completions` 接口 |
| `systemPrompt` | 见配置 | 翻译提示词，可自行调风格 |
| `timeoutMs` | `2500` | 单条超时，超时放弃、不阻塞聊天（DeepSeek flash 实测约 0.8s，留足余量） |
| `maxQueue` | `40` | 在途请求上限，刷屏时超出丢译文（原文照常显示） |
| `cacheSize` | `2000` | 译文 LRU 缓存条数 |
| `appendWaitMs` | `250` | 原文最多压多久等译文；超时先显示原文，译文晚到补一行 |
| `translateOutgoing` | `true` | 把中文发出去前翻成外语 |
| `outgoingTargetLanguage` | `en` | 发出去翻成什么语言 |
| `skipOwnMessages` | `true` | 自己说的话不翻译（含服务器回显的那条） |
| `translateSystem` | `true` | 系统位置播报的翻译开关（被 `onlyPlayerChat` 兜着，实际很少命中） |
| `onlyPlayerChat` | `true` | 只翻能认出「名字: 内容」的玩家聊天 |
| `skipIfAlreadyTarget` | `true` | 已是中日韩文则不翻 |
| `dedupSeconds` | `60` | 同一句多少秒内重复出现不再翻，`0` 关闭 |
| `skipShortMessages` | `10` | 去掉名字后字母数 ≤ 该值的短句不翻，`0` 关闭 |
| `skipPrefixes` | `["[NPC]"]` | 这些开头的消息不翻 |
| `skipWords` | gg/lol/ez… | 黑话白名单：整条只由这些词组成就不翻；清空 `[]` 关闭 |

### Tab 统计 `tab`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关，不想挂统计改 false |
| `hypixelApiKey` | 空 | Hypixel 官方 key（`official` 模式下留空 = 完全不查） |
| `apiBase` | `https://api.hypixel.net/v2` | 官方接口根地址 |
| `dataSource` | `auto` | `official` 只查官方 / `thirdparty` 只查第三方 / `auto` 官方不行就切第三方 |
| `thirdPartyBaseUrl` | `https://api.khadow.lol` | 第三方统计站，POST `/stats`，按用户名查 |
| `stat` | `bedwars_fkdr` | 也支持 `bedwars_wins` / `bedwars_final_kills` / `bedwars_ws` / `skywars_kdr` |
| `label` / `separator` / `valueColor` | `FKDR` / `" §7\| "` / `§b` | 显示样式 |
| `cacheMinutes` | `180` | 同一个人多久不重复查 |
| `maxRequestsPerMinute` | `30` | 令牌桶速率（官方额度真正的瓶颈是「每天 300 次」） |
| `concurrency` | `8` | 同时查几个人，别超 16 |
| `showPlaceholder` | `false` | 未查回来时先显示 `…` 占位 |
| `showUnknown` | `false` | 查不到（nick / 隐藏统计）时显示 `?` |
| `showRankPrefix` | `false` | 名字前补 `[MVP+]` 这类段位标签 |

### 调试

| 键 | 说明 |
|---|---|
| `debugSkipAuth` / `debugName` | 跳过微软登录，用假身份连离线服 |
| `debugSkipSessionJoin` | 跳过 sessionserver join（配合本地测试服） |
| `debugTab` | 把 Tab 相关原始包写到 `tab-debug.txt` |

### 数据源怎么选

- `official`：Hypixel 官方 `/v2/player`，要 key，每天 300 次，被 429 后整个冷却期内不查。最准，额度有限。
- `thirdparty`：按**玩家名**查，不消耗你的 Hypixel 额度；实测能返回 `core_fkdr`，但单次约 2 秒、只保证 FKDR，稳定性不由本项目控制。
- `auto`（默认）：先查官方；没 key、429、查不到时自动切第三方；两个都查不到才当「查不到」。官方冷却期间照常走第三方。

**查不到 ≠ 程序坏了**：nick 玩家、新号没玩过、关闭了 API 统计、第三方没覆盖，都会查不到。

## 工作原理

### 登录（上游侧，完整走一遍 1.8.9）

1. `Handshake(0x00)` → `LoginStart(0x00)`，名字用代理账号名，域名用 `handshakeHost`
2. `EncryptionRequest(0x01)`：生成 16 字节 shared secret，用服务端 RSA 公钥加密 secret 与 verifyToken
3. 计算 `sha1(serverId + secret + publicKey)`，调 sessionserver `join`（阻塞 HTTP，丢到独立线程池，不占用 Netty eventLoop）
4. 回 `EncryptionResponse(0x01)`，随后启用 **AES/CFB8**（key = IV = shared secret），加解密发生在帧层之前
5. `SetCompression(0x03)` → 后续包走 `[未压缩长度][zlib 数据]`，未压缩包写长度 `0`
6. `LoginSuccess(0x02)` → 两端进入 PLAY，变成纯中继

客户端侧则**故意不发** `EncryptionRequest`，所以客户端到代理这一段不需要加密，代理能直接读包。

### 聊天

- 下行 `0x02`：解析 JSON 组件（含 `translate` 键）拍平成 `Steve: 内容`，过滤后送 LLM；`ChatOutbox` 按 seq 出队，先发原包、再发 `└ 译文`。
- 译文行固定用 `CHAT` 位置：1.8.9 的 `SYSTEM` 位置走「固定行号」通道，新消息会顶掉上一条同位置的，原文和译文会互相吃掉。
- 上行 `0x01`：只有含中日韩文字才翻；聊天命令白名单 `pc ac gc sc shout p party g guild o officers msg m w tell whisper r reply me channel c`，只取第一个汉字之后的部分作为正文翻译，命令与玩家名原样。
- 兜底：翻译超时/失败/队列满 → 原消息照发，不丢消息。

### Tab

1.8 的玩家列表包 `0x38` 有独立的 `displayName` 字段（还有 action=3 单独更新它）。Tab 一行 = 队伍前缀 + displayName + 队伍后缀，而**头顶名牌不用 displayName**，所以只改 `displayName` 就能在 Tab 名字后加字而不动名牌。

代价是：一旦下发了 displayName，客户端就不再套 scoreboard 队伍 —— 名字会变纯白、队伍前缀丢失。所以代理同时抄了一份 `0x3E` 队伍包（前缀 / 后缀 / 颜色），挂统计时自己拼回去；服务器给的 displayName 则原样保留，一个字不改。

## 隐私与安全

- **聊天内容会发给 LLM**：`translate.baseUrl` 指向谁，聊天就发给谁。别把服务端地址配成不信任的第三方。
- **玩家名会发给第三方统计站**：`dataSource` 为 `auto` / `thirdparty` 时，Tab 里玩家的用户名会 POST 到 `thirdPartyBaseUrl`。
- **`auth.json` 里是明文 refresh token**：程序会尽量把权限设成 `600`；Windows 不支持 POSIX 权限，请自行注意。**不要提交、不要分享**。
- **`listenHost` 默认只监听本机**：改 `0.0.0.0` 后同网段任何人连上来都用你的号。
- 代理不落盘记录聊天，只在控制台打印译文（`[译] 原文 => 译文`）。
- 日志会打印 Hypixel key 的前 6 位用于排查，完整 key 不会输出。

## 已知限制与未验证项

1. **一个实例一个账号一个会话**。多人共用要改造成账号池。
2. **只支持 1.8.9（协议 47）**，不做版本翻译。
3. **Tab 队伍信息晚于玩家列表时，首帧会丢队伍前缀/队色**：队伍包之后到达没有触发重绘。
4. **翻译吞吐有限**：4 线程 + 单条超时，刷屏会丢译文（不会卡聊天）。要提升得改批量请求。
5. **走公共加速IP 时出口 IP 是共享的**，Hypixel 侧风控可能受影响。
6. **Tab 缓存不跨会话**：重连后是新的会话，会用同一份额度重新查一遍。
7. **无鉴权的本地接口（Ollama 等）只能用于收消息翻译**：`translate.apiKey` 为空时代理不会拦截要发出去的中文，填任意非空字符串可绕过。
8. 未在真实 Hypixel 上验证：真实账号登录与 sessionserver、加速IP 实际行为、真实 Hypixel Tab 结构下 displayName 的效果（会不会被队伍前缀压掉、刷不刷新）、长时间挂机稳定性、API key 在真实限速下的表现。

## 常见问题

**Tab 上没数字？** 先确认 `tab.enabled=true` 且 key 不为空；再看是不是 nick 玩家 / 关闭了 API 统计 / 今天的 300 次额度用完了。两个数据源都查不到就按「查不到」处理（默认不显示任何东西）。

**名字变白了、队伍称号没了？** 见上文 Tab 一节：displayName 会覆盖队伍渲染，代理会尽量补回队伍前缀，但队伍包晚到时补不回来。

**译文有时晚一行？** 原文最多等 `appendWaitMs`，超时先放原文，译文后到会单独补一行。缓存命中则立即出。

**能给朋友用吗？** 不能：一个微软账号同一时间只能顶一个会话，第二个连接会把第一个顶掉。

**支持 1.9 以上吗？** 不支持，协议号写死 47。

## 免责声明

- 本项目与 Mojang、Microsoft、Hypixel 没有任何关系。
- 仅用于学习 Minecraft 网络协议与个人自用。使用前请自行确认目标服务器的服务条款；**使用非官方客户端/代理可能被判定违规并导致封号**，风险自负。
- 登录走的是微软设备码流程 + 非官方 launcher 客户端 ID，属于灰色地带，随时可能失效。
- 请勿用本项目做自动发包、自动行走、刷分等自动化行为 —— 那会让性质完全改变。

## 目录结构

```
src/main/java/com/mcproxy/
├── Main.java                入口
├── Config.java              配置模型（带默认值与缺失字段兜底）
├── ProxyServer.java         监听（“代理的服务器”那一端）
├── Session.java             一次会话：两条连接绑一条命
├── auth/AuthService.java    微软设备码登录 + sessionserver join
├── proto/                   VarInt / 帧 / 压缩 / AES / 两侧 handler / Tab 探针
├── chat/                    组件拍平、LLM 客户端、翻译队列、聊天出口队列
└── tab/                     Tab 统计注入、队伍前缀副本、诊断日志
```

## License

[MIT](LICENSE)。协议细节参考 [wiki.vg](https://wiki.vg/) 与 Minecraft 1.8.9 客户端实现。
