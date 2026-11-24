# HypixelTranslate-Proxy

**Local proxy for Minecraft Java 1.8.9 (protocol 47) — chat translation in both directions, plus stats in the Tab list.**

[中文文档](README.md)

![Java](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)
![Minecraft](https://img.shields.io/badge/Minecraft-1.8.9%20(protocol%2047)-62B47A)
![License](https://img.shields.io/badge/License-MIT-blue)

The proxy **terminates** your client connection locally (so the client side is plaintext), then performs a real login to the upstream server with your own Microsoft account. That is why chat can be read, translated and rewritten.

```
client ──▶ proxy 127.0.0.1:25565 ──▶ upstream (accelerator IP / mc.hypixel.net) ──▶ server
           ↑ plaintext, readable            ↑ encrypted, proxy's own session
```

> Chat reading, translation and display-name rewriting only. No packet automation, no auto-walking, no farming.

## Why not just an accelerator IP?

|  | Accelerator IP | This proxy |
|---|---|---|
| Path | L4 dumb tunnel, only rewrites the handshake hostname | Terminates the client connection, then logs in separately |
| Can read chat | No — encrypted end to end | Yes — plaintext exists on your machine |
| Cost | None | One account = one session; needs Java locally |
| Purpose | Better routing only | Translation / packet rewriting / stats |

## Features

- **Incoming chat**: the translation is appended as a separate line (`└ …`), queued by sequence so lines never interleave.
- **Outgoing chat**: Chinese messages are translated before sending; for chat commands (`/pc`, `/msg`, …) only the body is translated, the command and player name are kept.
- **Token friendly**: slang (`gg`, `lol`, `ez`…), very short lines, repeated spam, `[NPC]` dialogue and non-player messages are skipped by default.
- **Tab stats**: FKDR and friends appended to each name. Data from the official Hypixel API, a third-party stats site, or `auto` fallback. Nameplates are untouched.
- **Custom MOTD** for the multiplayer list.
- **Lifecycle binding**: client disconnect kills the upstream session immediately, and vice versa.

## Quick start

Requires **Java 17+**.

```bash
java -jar HypixelTranslate-Proxy.jar
```

Or build from source:

```bash
mvn -DskipTests package
java -jar target/HypixelTranslate-Proxy.jar
```

First run generates `config.json`, prints a Microsoft device code (open <https://www.microsoft.com/link>), and stores the token in `auth.json` (mode 600).

Then set at least `translate.apiKey` (any OpenAI-compatible endpoint) and check `upstreamHost`.

In game: Multiplayer → Direct Connect → `127.0.0.1:25565`.

### Behind an accelerator IP

```json
{
  "upstreamHost": "1.2.3.4",
  "upstreamPort": 25565,
  "handshakeHost": "mc.hypixel.net"
}
```

`handshakeHost` **must stay `mc.hypixel.net`** — Hypixel routes by the hostname in the handshake, not by the IP you dialed.

## How it works

**Upstream login** — full 1.8.9 flow: handshake, `LoginStart`, RSA-encrypted shared secret and verify token, `sha1(serverId + secret + publicKey)` posted to the sessionserver (on a separate pool, never on the Netty event loop), `EncryptionResponse`, then AES/CFB8 (key = IV = shared secret) applied *before* framing, `SetCompression` with zlib, `LoginSuccess`.

**Client side** — the proxy deliberately never sends `EncryptionRequest`, so client → proxy traffic stays plaintext.

**Chat** — clientbound `0x02` JSON components are flattened (`{"translate":"chat.type.text"}` → `Steve: text`), filtered and sent to an OpenAI-compatible `/chat/completions` endpoint. The outbox re-emits the original packet followed by a `└ translation` line at the `CHAT` position (the `SYSTEM` position is a fixed-line channel in 1.8 and would overwrite itself). Serverbound `0x01` is only translated when it contains CJK; on timeout, failure or a full queue the original message is sent anyway.

**Tab** — 1.8's `0x38` has a `displayName` field used only by the Tab list (nameplates use the real name), so appending stats is a single `action=3` update. Since a non-empty `displayName` makes the client stop applying scoreboard teams, the proxy mirrors `0x3E` team packets (prefix/suffix/colour) and rebuilds them itself.

## Privacy and safety

- Chat content is sent to whatever `translate.baseUrl` points at.
- Player names are POSTed to `thirdPartyBaseUrl` when the stats source is `auto` or `thirdparty`.
- `auth.json` holds a **plaintext** refresh token (permissions forced to 600 where supported). Never commit or share it.
- `listenHost` defaults to `127.0.0.1`. Setting `0.0.0.0` lets anyone on your network use your account.
- No chat is written to disk; only the console shows `[译] original => translation`.

## Known limitations

1. One instance = one account = one session. Multi-user needs an account pool.
2. 1.8.9 only (protocol 47), no version translation.
3. If team packets arrive after the player-list packet, the first Tab frame loses the team prefix/colour.
4. Limited translation throughput: 4 workers and a per-message timeout; heavy spam drops translations (chat never blocks).
5. Shared exit IP with public accelerator IPs may affect Hypixel's risk scoring.
6. Not verified against the real Hypixel: real login/sessionserver, real accelerator behaviour, real Tab structure, long-run stability, real API rate limits.

## Disclaimer

Not affiliated with Mojang, Microsoft or Hypixel. For studying the Minecraft protocol and personal use only. Using an unofficial client/proxy may violate a server's terms and get your account banned — use at your own risk. The login flow uses Microsoft's device-code flow with a non-official launcher client ID; it may stop working at any time.

## License

[MIT](LICENSE).
