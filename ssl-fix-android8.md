# SSLHandshakeException on Android 8 — Analysis & Fix

## Symptom

Player links load in the UI, but clicking play shows a toast: "Помилка при завантаженні" /
"No links found". No exception reaches the user — the error is caught internally.

Affected providers: **KlonTV**, **Uakino**.
Unaffected providers: **UAFlix**, **Eneyida** (and others referencing the same player domains).

## Root Cause

Player embed domains recently switched to certificates from CAs not present in Android 8's
bundled CA trust store.

### ashdi.vip (used by KlonTV, Uakino, and others)

```
depth=0  CN=*.ashdi.vip
depth=1  C=US, O=SSL Corporation, CN=SSL.com TLS Issuing RSA CA R1   (issued Jul 15 2026)
depth=2  C=US, O=SSL Corporation, CN=SSL.com TLS RSA Root CA 2022
```

SSL.com's 2022 root CA is absent from Android 8's trust store. The leaf certificate was issued
Jul 15 2026, confirming the server recently switched to this new chain.

### tortuga.wtf (used by KlonTV, Uakino, and others)

```
depth=0  CN=tortuga.wtf
depth=1  C=US, O=Let's Encrypt, CN=YE2              (issued Jul 6 2026)
depth=2  C=US, O=ISRG, CN=Root YE
depth=3  C=US, O=Internet Security Research Group, CN=ISRG Root X2
```

ISRG Root X2 (EC-based, created 2024) is absent from Android 8's trust store. The older
ISRG Root X1 (RSA-based) IS present, but Let's Encrypt has since migrated to the X2 chain
for new certificates.

## Why UAFlix and Eneyida Are Unaffected

All four providers reference `ashdi.vip` / `tortuga.wtf` in their source code. The critical
difference is **where the player iframe points to** and **what URL is actually fetched via HTTPS**.

### Player iframe origins

| Provider | Player iframe selector | Iframe points to | `app.get()` target |
|---|---|---|---|
| UAFlix | `.video-box iframe[src]` | `uafix.net/vod/...` (own domain) | `uafix.net` |
| Eneyida | `.tabs_b.visible iframe[src]` | `eneyida.tv/...` (own domain) | `eneyida.tv` |
| KlonTV | `div.film-player iframe[data-src]` | `ashdi.vip/...` (external) | `ashdi.vip` |
| Uakino | `iframe#pre[src]` | `tortuga.wtf/...` or `ashdi.vip/...` (external) | `tortuga.wtf` / `ashdi.vip` |

**UAFlix & Eneyida** — `tortuga.wtf` is only a **referer header value** passed to
`M3u8Helper.generateM3u8()` and `ExtractorLink`. No HTTPS request is ever made to
`tortuga.wtf`. The actual player page fetch goes to the provider's own domain, which has
a valid SSL certificate.

**KlonTV & Uakino** — `ashdi.vip` / `tortuga.wtf` **is** the URL fetched via `app.get()`.
The player iframe embed points directly to these external domains.

## Fix Approach — Two SSL Layers

The SSL problem manifests at two independent layers:

| Layer | Component | Who controls it | Fix |
|---|---|---|---|
| HTTP fetch (player page) | OkHttp (NiceHttp) | Extension plugin | Trust-all `OkHttpClient` |
| Video streaming (m3u8/.ts) | ExoPlayer / Cronet | CloudStream app | Local video proxy server |

### Layer 1: HTTP fetch — Trust-all OkHttpClient

Replaced `app.get()` calls that fetch from `ashdi.vip` / `tortuga.wtf` with a dedicated
`OkHttpClient` that bypasses certificate validation entirely.

#### Why Conscrypt didn't work

Conscrypt 2.5.2 was the first attempt — it bundles modern CAs (including SSL.com 2022 and
ISRG Root X2). However, Android 8's platform `RootTrustManager` **overrides** whatever
`SSLContext`/`TrustManager` the extension provides:

```
org.conscrypt.ConscryptEngineSocket$2.checkServerTrusted
  → android.security.net.config.RootTrustManager.checkServerTrusted
    → com.android.org.conscrypt.TrustManagerImpl.verifyChain
      → CertPathValidatorException: Trust anchor for certification path not found
```

The platform's network security config intercepts all TLS verification regardless of which
provider/SSLContext/TrustManager the extension configures at the OkHttpClient level.

#### Trust-all implementation (what works)

Each provider inlines the trust-all client as a lazy property:

```kotlin
private val bypassClient by lazy {
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
    OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
```

Used via `fetchBypass(url, referer)` which replaces all `app.get()` calls to ashdi.vip/tortuga.

### Layer 2: Video streaming — Local proxy server

After the trust-all client successfully extracts the m3u8 URL, **ExoPlayer/Cronet** tries to
stream the video. Cronet (Chromium's network stack) has its **own TLS trust store** separate
from Java/OkHttp, and also lacks the SSL.com root CA on Android 8:

```
chromium: [ERROR:net/socket/ssl_client_socket_impl.cc:944] handshake failed; returned -1,
  SSL error code 1, net_error -202

ExoPlaybackException: Source error
  Caused by: CronetDataSource$OpenException: net::ERR_CERT_AUTHORITY_INVALID,
    ErrorCode=11, InternalErrorCode=-202, Retryable=false
```

Extensions **cannot** configure ExoPlayer's data source — Cronet is controlled by the
CloudStream app. The solution is a **local HTTP proxy server** that:

1. Starts a `ServerSocket` on an ephemeral port (daemon thread)
2. ExoPlayer connects to `http://127.0.0.1:{port}/?url=<encoded_url>` (no SSL needed)
3. The proxy fetches the original URL via the trust-all OkHttpClient
4. For m3u8 responses, rewrites all URLs in the content to also go through the proxy
   (including `URI="..."` in `#EXT-X-MAP` and similar tags)
5. Streams the response back to ExoPlayer

This pattern was adapted from the subtitle proxy already used by `AnimeONProvider`.

#### Proxy implementation

```kotlin
class VideoProxy(private val client: OkHttpClient) {
    private var server: ServerSocket? = null

    fun start() { /* starts accept loop on daemon thread */ }
    private fun handle(socket: Socket) { /* forwards request, rewrites m3u8 */ }
    private fun rewriteM3u8(content: String, baseUrl: String): ByteArray { /* ... */ }
    fun wrap(url: String): String =
        "http://127.0.0.1:${server!!.localPort}/?url=${URLEncoder.encode(url, "UTF-8")}"
}
```

### API-level gating

The proxy only activates on Android 8 and below (API < 28). On modern devices, m3u8 URLs
are passed directly to ExoPlayer without any proxy overhead:

```kotlin
private val videoProxy by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
        VideoProxy(bypassClient).also { it.start() }
    else null
}

// Usage: proxy wraps URL on old devices, passes through on modern ones
streamUrl = videoProxy?.wrap(m3u8Url) ?: m3u8Url,
```

## Implementation — Inlined Code

The trust-all client and video proxy are **inlined** directly into each provider class. A shared
`SslUtils` module was attempted but doesn't work: CloudStream's plugin system loads each `.cs3`
file in an isolated classloader — transitive `implementation` dependencies are not bundled into
the `.cs3` dex, causing `ClassNotFoundException` at runtime.

### What's in each provider

Both `KlonTVProvider` and `UakinoProvider` contain identical implementations of:

1. **`bypassClient`** — lazy trust-all `OkHttpClient` with custom `SSLContext` / `TrustManager`
2. **`VideoProxy`** inner class — local HTTP server on ephemeral port, rewrites m3u8 URLs
3. **`fetchBypass(url, referer)`** — suspend function using `bypassClient` instead of `app.get()`

The `VideoProxy` and `bypassClient` are only instantiated on API < 28 (Android 8 and below).

## Changed files

| File | Change |
|---|---|
| `KlonTVProvider/src/main/kotlin/.../KlonTVProvider.kt` | Inlined `bypassClient`, `VideoProxy`, `fetchBypass` |
| `UakinoProvider/src/main/kotlin/.../UakinoProvider.kt` | Inlined `bypassClient`, `VideoProxy`, `fetchBypass` |

## Other providers using ashdi.vip / tortuga.wtf (not yet fixed)

DoramyWorldProvider, KinoTronProvider, AnimeONProvider, CikavaIdeyaProvider,
SerialnoProvider, AnitubeinuaProvider, AnimeUAProvider, KinoVezhaProvider.

These are only affected if their player iframe points directly to `ashdi.vip` / `tortuga.wtf`
(rather than hosting the player on their own domain). They would need the same fix pattern:
inline `bypassClient` + `VideoProxy` + `fetchBypass`, use `fetchBypass()` for player page
fetches, and `videoProxy?.wrap()` for m3u8 URLs.

## Uakino-Specific: Why "No links found" Instead of an Exception

The flow for Uakino:
1. `loadLinks()` is called when user clicks play
2. For series: AJAX call to `uakino.best` succeeds (loads episode list)
3. For each episode: `extractPlayerJs()` calls `app.get(url)` to fetch player page from
   ashdi.vip/tortuga
4. SSL handshake fails — exception is caught internally
5. `scriptData` ends up empty or the exception propagates as a generic error
6. `m3uLink` stays empty — no `callback` is invoked
7. `loadLinks()` returns `true` (indicating "done")
8. CloudStream shows "No links found" because zero `ExtractorLink`s were reported

With the fix, steps 3-4 use `fetchBypass()` which succeeds, the m3u8 URL is extracted and
proxied, and ExoPlayer streams via localhost.

## Known issue references

- ExoPlayer issue [#9851](https://github.com/google/ExoPlayer/issues/9851) —
  `CronetDataSource$OpenException: net::ERR_CERT_AUTHORITY_INVALID`
- CloudStream upstream — CronetDataSource added for better caching/networking; Conscrypt
  used for HTTP but not for ExoPlayer's Cronet layer
