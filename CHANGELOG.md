# Changelog

All notable changes to the Zerochan Android client. Releases are published continuously to the
rolling `rolling` tag on GitHub, so the newest build is always at
https://github.com/berkchy/zerochan-android/releases/tag/rolling

Download artifacts are kept for **24 hours** as workflow artifacts. The rolling GitHub Release,
however, keeps only the latest set of APKs and never expires.

## How to install

Grab `app-release.apk` (or `app-debug.apk`) from the release, allow "install from unknown
sources" once, then install. The release APK is signed with the project's keystore, so the
debug and release variants can be installed side by side (different application ids).

Minimum Android 7.0 (API 24), built against API 35.

---

## [Rolling] - Unreleased

### Added

- **Latest feed** – everything newest-first from `/?p=<n>&json`, 48 posts per page, infinite
  scroll. Paging switches to zerochan's `?o=<id>` id-offset automatically past page 500 so the
  history never runs out.
- **Popular today** – ranked listing from `/popular`.
- **Search** – resolves `/search?q=` onto the canonical tag page (the site 301s and drops the
  `json` flag), then pages the result with `?s=id`. Trending tag chips on the home screen jump
  straight into a tag.
- **Post detail** – full preview with the `1341 x 2184` resolution line, file size, encoding
  format, favorite count, all tags grouped by type (character / series / outfit / mangaka /
  theme / source), the artist, the source url with a "Source" button and an Android share
  sheet.
- **Grouped posts** – posts that hold several images are collected into one swipeable pager,
  with a thumbnail strip underneath so you can jump straight to any of them. Swiping left and
  right walks the pictures of the post, and the related posts strip below continues from
  there.
- **Related images** – zerochan's own `/similar3` strip, tappable.
- **Image viewer popup** – tap any picture for the popup: the picture with its resolution
  underneath, a download button below the image and a second download button in the lower right
  corner of the picture itself, with a progress bar while the full resolution file is fetched.
- **Downloads** – full resolution files are saved into `Pictures/Zerochan` and published to
  MediaStore so they show up in the gallery app. No storage permission needed on Android 10+.
- **Library** – local favorites and a download list, backed by Room.
- **Material 3** – dynamic color on Android 12+, light/dark themes, adaptive launcher icon,
  edge-to-edge layout.

### Technical

- OkHttp interceptor reproduces zerochan's cookie handshake (`xbotcheck`) transparently, with
  the token-bound cookie persisted across app launches.
- `JsonEnvelope` extracts the json document that zerochan wraps in a stray `{ ... }` pair and
  follows with sidebar markup; listing endpoints answer `HTTP 500` with that valid body, so
  responses are validated by payload shape rather than status code.
- Favorite counts and multi-image groupings only exist in the desktop markup, so every listing
  page issues one extra html request to enrich the json payload.
- Coil mapper swaps `.avif` previews for the same-CDN `.jpg` on pre-Android 11 devices.

---

## Releases

| Tag | Contents |
| --- | --- |
| [`rolling`](https://github.com/berkchy/zerochan-android/releases/tag/rolling) | Latest build, continuously overwritten |