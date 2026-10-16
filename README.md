# Zerochan for Android

Material 3 / Jetpack Compose client for [zerochan.net](https://www.zerochan.net).

## Features

- **Latest feed** – `/` listing with infinite scroll (48 posts per page, `?p=`/`?o=` pagination).
- **Popular today** – ranked html grid.
- **Search** – `/search?q=` is resolved to the canonical tag page, then paged with `?s=id`.
- **Post detail** – full resolution, dimensions, size, format, favorites, tags, artist,
  source url, share sheet.
- **Grouped posts** – a post can hold several images; they are grouped into a swipeable pager
  and also listed as a thumbnail strip underneath.
- **Related images** – zerochan's own "similar" strip, tappable.
- **Image viewer** – tap any picture for the popup with the `WxH` line, a download button below
  the image and one in the lower right corner of the picture.
- **Downloads** – full resolution files saved into `Pictures/Zerochan` and published to
  MediaStore so they show up in the gallery.
- **Favorites** – stored locally in Room.
- **Material 3** – dynamic color, light/dark, adaptive launcher icon.

## How the site is consumed

zerochan has no open API but exposes everything through `?json` / `?xml`:

| Purpose | Endpoint |
| --- | --- |
| Latest listing | `GET /?p=<n>&json`, `GET /?o=<id>&json` |
| Tag listing | `GET /<Tag>?s=id&json` (+ `&p=` / `&o=`) |
| Popular | `GET /popular?p=<n>` (html) |
| Post detail | `GET /<id>?json` |
| Similar posts | `GET /similar3?id=<id>&ajax` (html fragment) |
| Search | `GET /search?q=<q>` → 301 to the tag page |

Details worth knowing:

1. Listings answer with **HTTP 500** but a valid body, and that body is a stray `{ ... }`
   wrapper around the real json document followed by the sidebar markup. `JsonEnvelope`
   cuts the balanced json out and the status code is ignored.
2. Pages cap at 500; past that the API asks for `?o=<lowest id of the previous page>`.
   `PageKey` switches to offsets automatically.
3. Every document request is gated by a cookie handshake: the server replies `503` with
   `/totally-innocent-logo-image.svg?<token>`, fetching that svg issues
   `Set-Cookie: xbotcheck=<value>`, and replaying the request then succeeds.
   `BotCheckInterceptor` performs this transparently.
4. Favorite counts and multi-image groupings only exist in the desktop html, so each listing
   page also issues one html request to enrich the json payload.

## App icon

The artwork lives in `design/source-logo.jpg` and every Android variant is generated from it:

```bash
python3 design/generate_icons.py design/source-logo.jpg   # needs Pillow
```

| Output | Where |
| --- | --- |
| `ic_launcher`, `ic_launcher_round` | `mipmap-<density>` - legacy launcher icons, 48dp |
| `ic_launcher_foreground`, `ic_launcher_background` | adaptive icon layers, 108dp canvas, artwork on the 80dp safe area |
| `ic_launcher_monochrome` | themed icon (Android 13+), luminance cut-out so the face survives a single-colour tint |
| `ic_splash_logo` | `drawable-nodpi`, splash screen |
| `play-icon-512`, `play-feature-graphic` | Play Store listing assets |

Five densities (mdpi to xxxhdpi), saved as WebP to keep the resource folder around 380 kB.
`design/icon-preview.png` shows the whole set as it renders in a launcher.

## Building

```bash
./gradlew assembleDebug      # debug apk
./gradlew assembleRelease    # minified release apk
```

## CI

`.github/workflows/android.yml` runs on every push and pull request:

1. builds the debug and release variants,
2. uploads them as workflow artifacts with **`retention-days: 1`** (they are pruned after
   24 hours),
3. publishes the APKs to the rolling GitHub Release
   [`rolling`](https://github.com/berkchy/zerochan-android/releases/tag/rolling), which is
   overwritten on each `main` build and never expires. The release body is generated from
   [CHANGELOG.md](CHANGELOG.md) plus a small "This build" table (version, commit, date, run
   link, signing status).

### Accounts

| Path | State |
| --- | --- |
| Sign in with username / password | Works. `POST /login`, no captcha on this endpoint. |
| Register | Works. reCAPTCHA v2 is rendered in-app; the user ticks it and the `g-recaptcha-response` token is posted with the form. |
| Sign in with Google | Works. The real zerochan `/login` page, and its real Google button, in an in-app WebView. |
| Import session | Works. Paste the `PHPSESSID` cookie from a signed-in browser session. |

Nothing but the session cookie is stored. The password is never written anywhere, and signing
out drops `PHPSESSID` / `guest_id` while keeping the bot-check cookie.

#### Why Google sign in runs the website

zerochan signs users in with Google Identity Services against **its own** OAuth client
(`280853676974-…apps.googleusercontent.com`), configured as:

```html
<div id="g_id_onload"
     data-client_id="280853676974-…apps.googleusercontent.com"
     data-ux_mode="redirect"
     data-login_uri="https://www.zerochan.net/oauth"></div>
```

`login_uri` makes GIS post `credential=<jwt>` straight to `/oauth`, and that endpoint verifies
the jwt against zerochan's client id. A token minted for any *other* client is refused with
`Invalid ID token, account unlinked?`.

That rules out the two obvious approaches:

- **Google's Android SDK** (`play-services-auth`, Credential Manager, `GetSignInWithGoogle`)
  exists to mint tokens for the *calling app's* client, not for a third party's, so it cannot
  produce a jwt `/oauth` would accept.
- **OAuth 2.0 authorization code + PKCE against `oauth2.googleapis.com/token`** fails for the
  same reason - the `id_token` comes back with *our* `aud`, not zerochan's. An earlier version of
  this app tried that and could never have worked; it is gone.

The credential only ever comes out of a real browser engine, so `GoogleSignInSheet` loads the
actual `/login` document in a WebView. The user taps the actual Google button, Google posts the
credential to `/oauth`, the server binds the account to a `PHPSESSID` in the WebView's cookie
jar, and that cookie is then installed in the app's own OkHttp jar (`SiteGoogleSignIn.seed` /
`harvest`, `ZerochanAccount.adoptWebSession`). From there the app is signed in exactly as it
would be after a password sign in, and the WebView is thrown away. No Google SDK, no OAuth
client registration, nobody to ask.

Two details the WebView needs to get right:

- It uses the same desktop Chrome `User-Agent` as every other request. `xbotcheck` is tied to the
  agent that solved it, and Google refuses OAuth from clients it detects as embedded WebViews,
  which it does from the `; wv` token a stock WebView sends.
- Only `xbotcheck` / `guest_id` / `z_theme` are seeded into it. A `PHPSESSID` is never seeded, so
  an existing session can never be mistaken for the one Google is about to create.

If the Google account has no zerochan account yet, `/oauth` answers `200` with the rejection as
the page body; that text is read back so the sheet can say exactly that instead of failing
vaguely. Register on the website first, then link Google in the account settings.

### Signing

Release signing is opt-in and driven by repository secrets:

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64` | base64 of the `.jks`, decoded by the workflow |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

When those are absent the workflow generates an ephemeral key so every run still produces an
installable APK. Locally, dropping a keystore at `app/keystore/zerochan.jks` (or pointing
`KEYSTORE_PATH` somewhere else) is enough - no code change required.

## Stack

Kotlin 2.0, Compose BOM 2024.09, Material 3, Paging 3, Room, Coil 2, OkHttp, Jsoup,
kotlinx.serialization, Navigation Compose, min SDK 24, target/compile SDK 35.
