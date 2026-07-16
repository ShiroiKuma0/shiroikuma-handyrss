# 白い熊 Handy RSS

A personal, rebranded Android RSS reader — a fork of
**[Handy News Reader](https://github.com/yanus171/Handy-News-Reader)** (itself a fork of
[Flym](https://github.com/FredJul/Flym)). It installs **side-by-side** with the official app
(`shiroikuma.handyrss`), with its own icon and name, fully signed for self-install.

**Latest release:** `1.1.4+41` · arm64 (universal) · Android 4.0+ · F-Droid flavor (no GMS).

## What this fork adds on top of Handy News Reader

- **Side-by-side install** — its own package id, content-provider authorities, launcher icon, and name,
  so it coexists with the official Handy News Reader and never collides on updates.
- **Grid & card article layout** — switch between classic list rows and an image-top card grid; tune
  columns, title-line count, and image height live from the top bar.
- **Fully configurable per-surface fonts** — independent font family, weight, and size for the article
  title, the in-list text, the feed/category drawer, and the reading view's body & headings — using
  bundled fonts or your own font files.
- **Black / yellow chrome theme** — a high-contrast dark theme applied to every surface (window, toolbar,
  drawer, menus, dialogs, action icons), with user-customizable chrome and article-content colors.
- **白い熊 Handy RSS UI screen** — one consolidated screen for all fonts & colors, reachable instantly by
  long-pressing the drawer's settings icon or the top-left home icon.
- **Export / Import** — boxed sections to export and import your **app settings**, your **RSS channels &
  feeds**, and full **auto-backups**, each to a folder you choose (with the last-export time shown).
- **Thunderbird-compatible feed export** — export your categories and feeds as OPML that imports into
  **Mozilla Thunderbird** as folders, each with the individual feeds inside (readable separately).
- **Scheduled auto-backup** — periodic full backup (feeds, starred articles, filters, labels, settings)
  to a folder of your choice, on a configurable interval.
- **Quick-control toolbars** — adjust text size, title size, and body font in the reading view, or
  columns / title lines / image size in the grid, on the fly; star/unstar an article with one tap from
  the always-visible ★ in the reading toolbar.
- **Paywall bypass for Mafra sites** — articles from lidovky.cz and idnes.cz load their full text past
  the pay-or-consent wall (fetched with a Googlebot user agent).
- **Tracks upstream** — regularly rebased onto the latest Handy News Reader development (full-width
  article images, feed multiselect delete, network-timeout fixes, and more from upstream master).

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
