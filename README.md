# 白い熊 Handy RSS

A personal, rebranded Android RSS reader — a fork of
**[Handy News Reader](https://github.com/yanus171/Handy-News-Reader)** (itself a fork of
[Flym](https://github.com/FredJul/Flym)). It installs **side-by-side** with the official app
(`shiroikuma.handyrss`), with its own icon and name, fully signed for self-install.

**Latest release:** [`1.1.6+008`](../../releases/latest) · arm64 (universal) · Android 4.0+ · F-Droid flavor (no GMS).

## What this fork adds on top of Handy News Reader

- **Side-by-side install** — its own package id, content-provider authorities, launcher icon, and name,
  so it coexists with the official Handy News Reader and never collides on updates.
- **Grid & card article layout** — switch between classic list rows and an image-top card grid with
  uniform card heights; tune columns, title-line count, and image height live from the top bar.
- **Fully configurable per-surface fonts** — independent font family, weight, and size for the article
  title, the in-list text, the feed/category drawer, and the reading view's body & headings — using
  bundled fonts or your own font files.
- **Black / yellow chrome theme** — a high-contrast dark theme applied to every surface (window, toolbar,
  drawer, menus, dialogs, action icons), with user-customizable chrome and article-content colors.
- **白い熊 Handy RSS UI screen** — one consolidated screen for all fonts & colors, styled with text-wide
  underlined headings and thin section separators, reachable instantly by long-pressing the drawer's
  settings icon, the top-left home icon, or the article list's top-right ⋮ button.
- **A backup that actually holds everything** — one ZIP, `shiroikuma-handyrss_<timestamp>.zip`,
  covering **nine categories**: feeds, your **articles** with their read/starred/scroll state,
  labels and filters, the **downloaded article text**, the **downloaded images**, your **installed
  font files**, and the four settings groups. Stock's own backup carries none of the last three, and
  restores font settings that point at fonts it never saved. Import restores only the categories you
  tick, and still reads every older plain-OPML backup.
- **A restore that lands complete** — importing a backup into an app that already has some of its
  feeds (a fresh install that just created them, a phone that has refreshed since) **merges** instead
  of skipping: every feed takes its own settings back — Show full article, Auto images load, text in
  list, auto-refresh, options — and its filters and articles follow, deduplicated, with read and
  starred state joined as a union that never undoes anything read on the phone. Stock skips any feed
  it already has, silently dropping its settings and every article under it.
- **Export / Import by category** — the UI screen's first section: one shared export folder (last
  export shown live) and a panel of category checkboxes behind round pill buttons. Every export is
  written to a `.part` file and renamed only once it is whole, so an interrupted backup never leaves
  a half-written archive that looks like a real one.
- **Headless automation backup** — an intent lets a companion automation app trigger the
  same export with no UI, pick the categories, choose the destination folder, and read back the
  written path and size. It reports live progress in real counts — naming the category it is on,
  the files and articles done, and the bytes written — never goes quiet for long enough to look
  dead, and can be **cancelled from outside** mid-export, cleanly and without a trace. **On by
  default**, with an optional 24-byte token you can switch on if you want callers to prove
  themselves; neither the switch nor the token ever travels inside a backup.
- **Back up the app itself, data and all** — a companion app can also ask for the whole archive
  down a file descriptor it opens, and hand it back on a wiped phone, so the app can be restored
  with its feeds, articles, images and settings rather than reinstalled empty. The caller is
  identified by the system and checked three ways — exact package name, a uid cross-check, and a
  pinned signing certificate — because it is the caller that supplies the destination. Restoring
  is only ever available through that identified channel, never over a broadcast.
- **Exports that finish** — the work runs in a foreground service holding a wakelock, not inside a
  broadcast receiver, so a long backup survives the screen going off instead of being killed
  part-way with nothing written and nobody told.
- **Storage that cleans up after itself** — deleting a feed or a group now reaps the downloaded
  images and article files that went with it, and the periodic cleanup no longer waits for the phone
  to be on a charger. Upstream leaves both behind indefinitely.
- **Thunderbird-compatible feed export** — your categories and feeds go out as OPML (`feeds.opml`
  inside the backup ZIP) that imports into **Mozilla Thunderbird** as folders, each with the
  individual feeds inside, readable separately.
- **Scheduled auto-backup** — periodic full backup (feeds, starred articles, filters, labels, settings)
  to a folder of your choice, on a configurable interval.
- **Quick-control toolbars** — adjust text size, title size, and body font in the reading view, or
  columns / title lines / image size in the grid, on the fly; star/unstar an article with one tap from
  the always-visible ★ in the reading toolbar.
- **Paywall bypass for Mafra sites** — articles from lidovky.cz and idnes.cz load their full text past
  the pay-or-consent wall (fetched with a Googlebot user agent).
- **Tracks upstream** — regularly rebased onto the latest Handy News Reader development, taking
  untagged `master` bumps too rather than waiting for a release. Currently on **`v1.1.6`**, which
  brings a screen keep-on duration setting, full-width images limited to books, a wakelock over long
  operations, and an FB2 footnote fix — on top of earlier pickups like feed multiselect delete,
  network-timeout fixes and the newest jsoup.

## Install

Download the APK from the [latest release](../../releases/latest) and install it (allow installing from
unknown sources). It updates cleanly over previous 白い熊 Handy RSS builds (stable signing key) and
coexists with the official app.

## Build

`fdroid` flavor, release build: `./gradlew :FlymFork:assembleFdroidRelease` signed with the project
keystore. The Java/Kotlin namespace stays `ru.yanus171.feedexfork`; only the `applicationId`, provider
authorities, app label, and icon are rebranded.

## Credits & license

Based on **Handy News Reader** by [yanus171](https://github.com/yanus171/Handy-News-Reader) and **Flym**
by Frédéric Julian. Licensed under the **GNU General Public License v3** (inherited). This is an
unaffiliated personal fork.
