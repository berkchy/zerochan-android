# Zerochan for Android

Material 3 / Jetpack Compose client for [zerochan.net](https://www.zerochan.net).

## Features

- **Latest feed** – paged listing, 21 posts per page, with favourite and multi-image badges.
- **Popular today** – ranked grid.
- **Search** – `/search?q=` is resolved to the canonical tag, then paged as a listing.
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

Listings come from the community run json mirror at
[`zerochan-api.vercel.app`](https://zerochan-api.vercel.app), which in turn reads zerochan:

| Purpose | Endpoint |
| --- | --- |
| Latest listing | `GET /api/posts?limit=<n>&page=<n>&include=engagement` |
| Tag listing | `GET /api/tags?tags=<tag>&tags=<tag>&include=engagement` |
| Popular | `GET /api/popular?limit=<n>&page=<n>` |
| Post group | `GET /api/posts/<id>` |
| Post detail | `GET /<id>?json` |
| Similar posts | `GET /similar3?id=<id>&ajax` (html fragment) |
| Search | `GET /search?q=<q>` → 301 to the tag page |

The rendered grid is the fallback, not dead code - see below.

Details worth knowing:

1. The site's own `?json` listings answer with **HTTP 500** but a valid body, and that body is a
   stray `{ ... }` wrapper around the real json document followed by the sidebar markup.
   `JsonEnvelope` cuts the balanced json out and the status code is ignored. That path is still
   used for post details.
2. `include=engagement` is what makes the mirror worth using: favourite counts and multi-image
   group sizes do not exist in the site's json at all, and the mirror reads them off the
   rendered grid for the whole page in **one** extra upstream request. Without it the badges on
   the grid would silently be empty.
3. `?p=` caps at 100 pages; past that the mirror and the site both want the id cursor
   (`?o=<lowest id of the previous page>`), which `FeedViewModel` switches to automatically.
4. Every document request to the site is gated by a cookie handshake: the server replies `503`
   with `/totally-innocent-logo-image.svg?<token>`, fetching that svg issues
   `Set-Cookie: xbotcheck=<value>`, and replaying the request then succeeds.
   `BotCheckInterceptor` performs this transparently.
5. The mirror is a third party, so it is treated as an accelerator only. When it is unreachable,
   rate limited, or has nothing to say (members-only boards such as Ecchi, and ambiguous single
   tags like `Rem` that the site itself will not resolve), `ZerochanClient` falls back to
   parsing the html grid - which is also the only thing that can tell a restricted tag apart
   from an empty one.
6. Mirror requests deliberately carry no cookies: a signed in user's `PHPSESSID` is never handed
   to a third party host. Details, downloads and everything else still go straight to the site.

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
| Register | Works. reCAPTCHA v2 is rendered in-app; the user ticks it and the `g-recaptcha-response` token is posted with the form. An image challenge ("select all the buses") grows the widget to fit and scrolls. |
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

- It uses the same desktop Chrome `User-Agent` as every other request, **and** overwrites the
  User-Agent Client Hints with `WebSettingsCompat.setUserAgentMetadata`. The `User-Agent` string
  alone stopped being enough years ago: Chrome also sends `Sec-CH-UA`, and a WebView brands
  itself `"Google WebView"` in there. `xbotcheck` is tied to the agent that solved it, so a
  stock WebView agent (`; wv`) would force the interstitial again as well.
- Only `xbotcheck` / `guest_id` / `z_theme` are seeded into it. A `PHPSESSID` is never seeded, so
  an existing session can never be mistaken for the one Google is about to create.

If the Google account has no zerochan account yet, `/oauth` answers `200` with the rejection as
the page body; that text is read back so the sheet can say exactly that instead of failing
vaguely. Register on the website first, then link Google in the account settings.

**This fights Google on purpose, and that has to be said plainly.** Google's OAuth policy
forbids running an authorization request in an embedded user-agent under the app's control, and
Google enforces it (`403 disallowed_useragent`, "this browser or app may not be secure"). The
overrides above are the only supported way to stop the WebView announcing itself, and they do
unblock it today - but Google is free to detect WebViews some other way tomorrow, and Play's
Guideline 4.8 rejects the pattern regardless. The sign in sheet watches for the refusal, and when
it comes it says so and points at **Settings → Account → Import**, which needs nobody's
cooperation and keeps working forever.

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
