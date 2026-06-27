---
name: publish-version
description: Publish the latest built + on-device-confirmed 白い熊 Handy RSS build as a GitHub release for the ShiroiKuma0/shiroikuma-handyrss fork — create a git tag (ALWAYS without a leading 'v'), refresh the fork README (a "fork of yanus171/Handy-News-Reader, with these major features on top" page + a curated major-features list with short descriptions), write a very specific changelog listing everything built, create the GitHub release, and set the GitHub default branch to `custom` so the repo landing page shows our work + README. Use when the user runs /publish-version, or asks to publish / release / tag / "put out" the current version to GitHub, update the GitHub README, or make the GitHub page land on our custom branch. Delegates nothing build-related — it publishes whatever `handy-rss-build` last produced and the user confirmed on-device.
---

# 白い熊 Handy RSS — publish a version to GitHub

Publishes the **current, already-built-and-on-device-confirmed** `custom` tip as a GitHub **release** on
`ShiroiKuma0/shiroikuma-handyrss`: a tag (no leading `v`), a refreshed fork **README**, a comprehensive
**changelog** (release notes), and the **default branch flipped to `custom`** so the repo page lands on
our work.

**READ `.claude/skills/handy-rss-build/SKILL.md` first** — its **"Feature commits on `custom`"** list is
the canonical record of everything this fork adds over stock. The README's major-features list and the
release changelog are both **derived from it** (don't invent features; pull them from there and the git
log). Where build/version mechanics matter, that skill wins.

This skill does **not** build. It publishes what's already on `custom` (built via `handy-rss-build`,
confirmed on-device, and pushed). If the latest work isn't pushed/confirmed yet, STOP and say so.

## Key facts (re-verify, don't trust blindly)

| Item | Value |
|------|-------|
| Repo (GitHub) | `ShiroiKuma0/shiroikuma-handyrss` |
| Publish branch | `custom` (the feature stack; this is what we release + make default) |
| Upstream (credit in README) | `yanus171/Handy-News-Reader` (itself a Flym fork) |
| Version string | the current `versionName` in `FlymFork/build.gradle` (e.g. `1.1.4+38`) = the last built+confirmed build |
| **Tag format** | **the version string, NO leading `v`** (e.g. `1.1.4+38`, never `v1.1.4+38`). Git allows `+` in tags. |
| Release title | the same version string |
| Default branch target | `custom` (currently the repo may still default to `master`) |
| Tooling | `gh` CLI (already authed as `ShiroiKuma0`); all `gh`/`git push` calls run **UNSANDBOXED** (network + `~/.ssh` + `~/.config/gh`) |

## Workflow

### Phase 0 — Preflight (STOP if any fails)

1. `cd ~/git/shiroikuma-handyrss`; on branch `custom`; **tracked** tree clean (`git status --porcelain | grep -vE '^\?\?'` empty — the repo-root dotfiles are untracked noise, ignore them).
2. `custom` is pushed: `git rev-parse custom` == `git rev-parse origin/custom`. If not, STOP — the thing to publish must already be on `origin/custom` (and on-device-confirmed). Don't publish unconfirmed work.
3. `gh auth status` OK.
4. **VERSION** = `grep -oE "versionName '[^']+'" FlymFork/build.gradle | head -1` → strip quotes → e.g. `1.1.4+38`. **TAG = VERSION verbatim, with any leading `v` removed.**
5. Tag not already taken: `git rev-parse -q --verify "refs/tags/$TAG"` should fail (else this version is already published — confirm with the user whether to re-tag/replace, or bump). Also `gh release view "$TAG"` should 404.

### Phase 1 — Refresh the README (on `custom`)

Rewrite `README.md` to the fork page (template below). The **major-features list is curated** — pick the
most important user-visible wins over stock Handy News Reader from the `handy-rss-build` feature-commit
list, each a one-line bold title + short friendly description (not the raw commit text). Keep it to ~6–10
items; this is the shop window, not the changelog. Update the "Latest release" line to VERSION.

### Phase 2 — Comprehensive changelog (release notes)

Write release notes that **list everything built** — be specific. Source = the `handy-rss-build`
feature-commit list (commits 1–N) + `git log --oneline <upstream-base-tag>..custom`. Group it readably
(Rebranding / Layout / Fonts / Theme & colors / Settings & data / Misc) and don't omit features. This is
the exhaustive record; the README is the highlights. Save it to a temp file for `gh release create
--notes-file` (avoids shell-quoting pain with the long text + non-ASCII).

### Phase 3 — Review gate (outward-facing — confirm once)

Show the user: VERSION/TAG, the README major-features list, and the changelog. Creating a **public**
release + flipping the default branch is outward-facing and not trivially reversible — get a go-ahead
before Phase 4. (A `/publish-version` invocation is intent to publish, but confirm the *content* once.)

### Phase 4 — Publish (UNSANDBOXED)

1. Commit the README on `custom`: `git add README.md && git commit -m "README: <VERSION> fork overview + major features"` then `git push origin custom`.
2. **Tag (no `v`) + push:** `git tag "$TAG"` then `git push origin "$TAG"`.
3. **GitHub release:** `gh release create "$TAG" --repo ShiroiKuma0/shiroikuma-handyrss --target custom --title "$TAG" --notes-file <changelog-file>`. Optionally attach the APK: if `~/tmp/shiroikuma-handyrss_<VERSION>_arm64-v8a.apk` exists, add it as a release asset (`gh release create … <apk>` or `gh release upload`). Ask before attaching (it's a signed binary going public).
4. **Default branch → `custom`:** `gh repo edit ShiroiKuma0/shiroikuma-handyrss --default-branch custom` (idempotent; so github.com/ShiroiKuma0/shiroikuma-handyrss lands on `custom` + its README).
5. Report the release URL (`gh release view "$TAG" --web --json url -q .url`) and confirm the default branch is now `custom`.

## README template

Fill the bracketed bits. Style模仿 the user's other fork READMEs (e.g. `shiroikuma-futokxkb`): lead with
"a fork of X", then "on top of it we added these major features", then the curated list.

```markdown
# 白い熊 Handy RSS

A personal, rebranded Android RSS reader — a fork of
**[Handy News Reader](https://github.com/yanus171/Handy-News-Reader)** (itself a fork of
[Flym](https://github.com/FredJul/Flym)). It installs **side-by-side** with the official app
(`shiroikuma.handyrss`), with its own icon and name, fully signed for self-install.

**Latest release:** `<VERSION>` · built for arm64 (universal), Android 7+ · F-Droid flavor (no GMS).

## What this fork adds on top of Handy News Reader

- **Side-by-side install** — own package id, content-provider authorities, launcher icon, and name, so it
  coexists with the official Handy News Reader and never collides on updates.
- **Grid & card article layout** — switch between classic list rows and an image-top card grid; tune
  columns, title-line count, and image height live from the top bar.
- **Fully configurable per-surface fonts** — independent font family, weight, and size for the article
  title, the in-list text, the feed/category drawer, and the reading view's body & headings — using
  bundled fonts or your own font files.
- **Black / yellow chrome theme** — a high-contrast dark theme applied to every surface (window, toolbar,
  drawer, menus, dialogs, action icons), with user-customizable chrome + article-content colors.
- **白い熊 Handy RSS UI screen** — one consolidated screen for all fonts & colors, reachable instantly by
  long-pressing the drawer's settings icon or the home icon.
- **Export / Import** — boxed sections to export & import your **app settings**, your **RSS channels &
  feeds**, and full **auto-backups**, each to a folder you choose (with the last-export time shown).
- **Thunderbird-compatible feed export** — export your categories & feeds as OPML that imports into
  **Mozilla Thunderbird** as folders, each with the individual feeds inside (readable separately).
- **Scheduled auto-backup** — periodic full backup (feeds, starred articles, filters, labels, settings)
  to a folder of your choice, on a configurable interval.
- **Quick-control toolbars** — adjust text size, title size, and body font in the reading view, or
  columns / title lines / image size in the grid, on the fly.

## Install

Download the APK from the [latest release](../../releases/latest) and install it (allow unknown sources).
It updates cleanly over previous 白い熊 Handy RSS builds (stable signing key) and coexists with the
official app.

## Build

See `.claude/skills/handy-rss-build/SKILL.md`. In short: `fdroid` flavor, release build,
`:FlymFork:assembleFdroidRelease` signed with the project keystore.

## Credits & license

Based on **Handy News Reader** by yanus171 and **Flym** by Frédéric Julian. Licensed under the
**GNU GPL v3** (inherited). This is an unaffiliated personal fork.
```

## Changelog (release notes) — derive, be exhaustive

Generate from the `handy-rss-build` feature-commit list. As of `1.1.4+38` the full set is (keep this
current each publish — add new feature commits, never drop shipped ones):

- **Rebranding / packaging:** `shiroikuma.handyrss` applicationId + provider authorities + `fileprovider`
  + write-permission; `白い熊 Handy RSS` label across all locales; co-install with the official app;
  black/yellow line-art launcher icon (all densities); full product-name purge of UI strings + About page
  (repointed to this fork); co-install crash fix (stale explicit-intent `targetPackage`); new-issue form
  de-branded.
- **Article list / layout:** image-top card grid layout; configurable row vs grid (`list_layout_grid`),
  grid columns, title-line cap, and grid image height; restyled mark-all-read FAB (black fill, yellow
  tick + ring); top-bar live quick-control sliders (columns / title lines / image height) + an
  article-title font picker.
- **Fonts:** per-surface fonts (family + weight + size), independent per surface — article title, in-list
  text, drawer, reading-view body, reading-view headings — via bundled or user font files; reading-view
  toolbar (text size / title size / body font, live); the fonts settings screen redesign.
- **Theme & colors:** drawer (left nav) black/yellow recolor; menus + settings + window background
  recolor; full chrome black/yellow pass (icons, compound buttons, feed-list row); content-color pickers
  (article bg, quote, subtitle); chrome-color pickers `chrome_bg`/`chrome_fg` driving window/nav/drawer/
  FAB/toolbar/menu icons/dialogs (D2a–D2e); runtime `Theme.TintDialog` across all AlertDialogs;
  chrome-styled toasts (black bg / yellow text / yellow border) app-wide.
- **白い熊 Handy RSS UI screen & data:** the "Fonts" screen renamed to **白い熊 Handy RSS UI**; long-press
  the drawer settings icon or the home/top-left icon to deep-link to it; strict 16/50/100 dp 3-tier
  indentation; **Export/Import** section — settings, RSS channels & feeds, and auto-backup blocks, each
  with a SAF directory picker + "last export" + export/import buttons; auto-backup moved here (toggle,
  interval, toast, charging) with backup-to-chosen-folder; **Thunderbird-native feeds OPML** (per-feed
  wrapper folders, self-closing `type=rss`+`version=RSS`, round-trip-safe importer, `.opml` extension).
- **Misc:** main-screen top-left launcher icon (24 dp, aligned) with long-press → UI screen; `+N` version
  scheme; `buildFeatures{ buildConfig true }` root-fix.

## Hard rules

- **Tags ALWAYS without a leading `v`** (the version string verbatim, e.g. `1.1.4+38`). This is the user's
  explicit convention for this fork's release tags. (Note: the old `shiroikuma-v…` snapshot tags used a
  prefix — release tags from this skill do NOT.)
- **Publish only built + on-device-confirmed + pushed `custom`.** Never publish unconfirmed or unpushed
  work. If `custom != origin/custom`, STOP.
- **Confirm the content once before the public release** (release notes + README + default-branch flip are
  outward-facing). The invocation authorizes publishing; the confirm is on *what* ships.
- **README = highlights, changelog = exhaustive.** Curate the README's major features; the release notes
  list everything from the feature-commit record.
- **Default branch → `custom`** so the GitHub page shows our work. Idempotent — safe to re-run.
- **Keep the lists current.** Each publish, refresh the README features + changelog from the latest
  `handy-rss-build` feature-commit list (and `git log`); add new commits, never drop shipped features.
- `gh` / `git push` / `gh repo edit` run **UNSANDBOXED**.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with
Claude" trailer to commits, tags, or release notes; end at the last line of the body. (Global rule:
`~/.claude/CLAUDE.md`.)
