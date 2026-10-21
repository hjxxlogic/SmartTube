# Built-in proxy (v2ray) — design notes

Branch: `feature/proxy`. Status: in progress.

## Owner requirements

1. Built-in **v2ray engine**, running **in-process**, used **only by this app**
   (no `VpnService`, no TUN device, no system-wide proxy).
2. **Fail-closed**: while the built-in proxy is enabled, *all* app traffic goes
   through the engine. If the engine is down, requests fail — nothing silently
   falls back to a direct connection.
3. Phone-based configuration through a **general settings framework** (a
   registry of setting sections: id / title / HTML renderer / save handler).
   The proxy section is the first — and currently only — section.

## What already existed

SmartTube already ships a proxy *client* (`common/.../common/proxy/`):
`ProxyManager` / `Proxy` / `WebProxyDialog`, HTTP/SOCKS with optional auth,
persisted in `AppPrefs`, applied at startup by `SplashPresenter` and consumed
from system properties by `OkHttpCommons`. This feature keeps that plumbing and
adds the missing pieces: an engine, a config channel, and a fail-closed audit.

## Architecture

- **Engine**: AndroidLibV2rayLite **v5.55.0** (`libv2ray.aar`, upstream
  https://github.com/2dust/AndroidLibV2rayLite/releases/tag/v5.55.0,
  sha256 of the original AAR:
  `1acfb2da725b8fe4356f1219e6748dc564dac2e3cd8b307c1909c3831196ecce`).
  Its manifest declares `minSdkVersion 21`, so it runs on the target
  Android 6.0 TV (API 23). The copy in `common/libs/` is the same AAR with the
  unused `x86` / `x86_64` JNI directories removed (TVs are ARM); classes,
  manifests and geo assets are untouched.
  `Libv2ray.initCoreEnv()` + `CoreController.startLoop(configJson)` run the
  core in-process. Inbounds: SOCKS5 on `127.0.0.1:10808` and HTTP on
  `127.0.0.1:10809`, loopback only. v2ray DNS is configured so name resolution
  happens through the tunnel (no LAN DNS leak).
- **Wiring**: in built-in mode the existing proxy plumbing is pointed at the
  loopback inbounds (SOCKS URI in `AppPrefs` + `ProxyManager`), and the
  `http`/`https` system properties are pointed at the loopback HTTP inbound so
  `HttpURLConnection` consumers (ExoPlayer default data source, Glide) are
  covered too. A fail-closed `ProxySelector` is installed as the default while
  built-in mode is on.
- **Modes**: Off / External (legacy behavior, unchanged) / Built-in v2ray,
  chosen in Settings → Network settings, with engine status, active node,
  a connection test and the "Configure by phone" entry in the same section.
- **Config channel**: an embedded NanoHTTPD server that runs **only** while
  the QR screen is open (hard stop after 5 minutes). The QR code encodes
  `http://<TV LAN IP>:<port>/?t=<one-time token>`; the token dies on the first
  successful save or when the server stops. The page is rendered from the
  section registry in `common/.../common/settingsserver/`. The proxy section
  accepts: a share link (`vmess://`, `vless://`, `trojan://`, `ss://`), a
  subscription URL, or a raw v2ray JSON config; after a subscription fetch it
  offers the node list with an active-node picker. Secrets are write-only:
  never rendered back, never logged.
  The subscription/bootstrap fetch is a **direct** connection by design (it
  downloads configuration, it is not app content traffic) and uses an explicit
  `Proxy.NO_PROXY` client, marked in code.
- **Fail-closed audit**: every HTTP path in the app was checked — Retrofit /
  YouTube API, SponsorBlock, ExoPlayer (OkHttp / Default / Cronet data
  sources), Glide, lounge polling, the app-update downloader, WebView. Paths
  that bypassed the shared proxy-aware factory were patched (details in the
  commit history): lounge polling now uses `OkHttpCommons.setupBuilder`, and
  the Cronet player data source is disabled while built-in mode is on
  (Cronet does not honor the app proxy configuration).

## Lifecycle

If built-in mode is enabled and a config exists, the engine is started during
app startup (from `SplashPresenter`, before content loads), restarted when the
configuration changes, and stopped when the mode is switched off. Connection
test: HTTPS GET `https://www.google.com/generate_204` through the local SOCKS
inbound, 10 s timeout.
