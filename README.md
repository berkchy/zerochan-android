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

## Building

```bash
./gradlew assembleDebug      # debug apk
./gradlew assembleRelease    # minified release apk
```

CI (`.github/workflows/android.yml`) runs lint, unit tests and both variants on every push,
and uploads the APKs as a build artifact.

Release builds are signed when `app/keystore/zerochan.jks` exists (see `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`).

## Stack

Kotlin 2.0, Compose BOM 2024.09, Material 3, Paging 3, Room, Coil 2, OkHttp, Jsoup,
kotlinx.serialization, Navigation Compose, min SDK 24, target/compile SDK 35.
