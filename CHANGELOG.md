# Changelog

This file carries **both histories**: the 白い熊 Handy RSS fork's releases below, newest first, and —
were upstream ever to add a root changelog — theirs underneath, untouched. Upstream Handy News Reader
keeps its own per-release notes in `fastlane/metadata/android/en-US/changelogs/`.

Each fork release names the upstream release it is built on. The build counter in a version
(`1.1.6+003`) is zero-padded so releases sort in build order.

---

## 白い熊 Handy RSS 1.1.6+006 — 2026-09-04

Built on upstream **`v1.1.6`** (versionCode 341) — the same upstream base as `1.1.6+003`, so
everything here is fork work.

The 保存復元 backup contract moved to **v2**. The case it now has to serve is 応用管理 restoring apps
**and their data** onto a wiped phone, where nothing has been configured and nobody has pasted
anything — so the gate that used to depend on a pasted secret had to stop depending on one, and the
app gained a second door that hands its whole archive to a caller the system can identify.

### The gate opens by default
- **Automation export is now ON out of the box**, and the authorization token became an **opt-in**
  under a new 「Use authorization token?」 switch. There is nothing to turn on and nothing to paste
  for this app to answer the backup batch. The master switch stays, because it is the only way to
  close this app off — a feature that can be turned on but never off is one you cannot retreat from.
- **A token sent to an app that is not asking for one is ignored, never refused.** Tokens outlive
  the settings they were pasted for, and refusing them would turn "the switch was turned off" into
  "half the batch mysteriously fails".
- **The token row is hidden unless the token is actually being asked for** — a 48-character secret
  sitting under an off switch only invites pasting it somewhere it will do nothing.
- Both checks now live in **one function** rather than being written out at each entry point, so
  "automation disabled" and "bad token" cannot drift apart — they are different faults with
  different fixes and are reported separately.
- The new preference joins the other two in the export-exclusion list, so it can never ride inside
  a backup.

### Back up the app itself, data and all
- **A new data door** lets a companion app ask for the complete archive and hand it back on a wiped
  phone, so the app can be restored with its feeds, articles, downloaded text, images and settings
  rather than reinstalled empty.
- **The caller is identified by the system and checked three ways** — exact package name, a uid
  cross-check the kernel answers, and a pinned signing certificate. Not a name prefix: package names
  are not a namespace anyone owns, and since the caller supplies the destination, a prefix check
  would have been weaker than the token it replaces.
- **The archive travels down a file descriptor the caller opens** — not a path and not a URI. The
  caller stages its backup in a temporary directory it renames on commit, and encrypts and checksums
  each file it knows about, so a file this app dropped in itself would end up in plaintext and
  unverified inside an otherwise protected backup. A descriptor is also a capability that expires
  when it is closed.
- **Restoring exists only on that identified channel** and deliberately has no broadcast form: an
  import overwrites the app's data, and the broadcast half of the contract is open by design.
- Both directions run in a foreground service under a wakelock, report the same real-count progress
  as the existing export, and can be cancelled.

### Fixes found while building it
- **Replies to the app that actually drives the backup batch were being discarded.** The manifest's
  package-visibility list named only the restore app, so `setPackage()` on the reply silently failed
  on Android 11+ — the export ran, wrote correctly, and was never heard of. Both callers are now
  named, which also matters for the identity check: the calls that read a caller's certificate are
  visibility-filtered too, so an invisible caller failed as "signature unreadable" rather than
  merely losing its reply.
- **A restore could report success over settings that were never written to disk.** The restoring
  app force-stops this one the instant it reports success — correctly, since a live process would
  write its cached preferences back out and undo the import — but that stop is a kill, and the
  preference write was asynchronous. The import now flushes synchronously before answering. This was
  invisible in testing, because a hand-run import is followed by a normal shutdown that flushes it.
- **A refused service start no longer leaks the caller's open file.** Several things can fail in the
  window between accepting a job and the worker thread owning the descriptor — a background
  foreground-service start can be refused outright on Android 12+, the wakelock can throw, the
  thread can fail to start — and any of them would have held the caller's file open and left it
  waiting. One guard now covers the whole window and answers with a real reason.
- **A refused start also raises the one repair the user can actually perform**, as a notification
  offering the battery-optimisation exemption, without blocking the reply.
- **Progress and replies from the data door carry the correlation id under both names** the family's
  callers read, so neither side needs a special case.

### Notes for other forks on this contract
- **A large import spools to disk rather than into a byte array.** Reading the whole archive before
  touching anything is the right rule — a partial read would otherwise import half an archive — but
  this app's backup carries its article corpus and roughly 3,700 images, so the bound has to be disk
  rather than memory.
- **The data service declares `dataSync`, not `specialUse`.** The latter is an API 34 value that the
  resource compiler rejects outright below that, which is a build failure rather than a preference.

---

## 白い熊 Handy RSS 1.1.6+003 — 2026-08-17

Built on upstream **`v1.1.6`** (versionCode 341). The first release in which the fork sits on a real
upstream tag again since the master-tip refreshes — `v1.1.6` is also upstream's `master` tip.

### Pulled in from upstream v1.1.6
- **Screen keep-on duration** — a new setting that holds the display awake for a chosen interval while reading.
- **Full-width images now only in books** (FB2/EPUB) rather than in every article.
- **A wakelock over long operations** — refresh, import, delete-old and full-text loads no longer stall when the CPU dozes.
- **FB2 footnote extraction fixed.**
- 45 translation updates across ~20 locales.

### Fork changes in this release
- **Build numbers are zero-padded to three digits** (`1.1.6+003`), so APKs and release tags sort in
  build order instead of `+10` landing before `+3`. Previously published unpadded tags are left as they are.
- **`gradlew` executable bit restored** — upstream shipped it non-executable as fallout of a repo-wide
  line-ending round-trip, which broke the build outright.
- **Duplicate `WAKE_LOCK` permission removed** — upstream's new declaration for its wakelock landed
  beside the fork's existing one for the export service; one declaration now serves both.
- **Ghost-sweep call site retargeted** to upstream's extracted `LongOper` class, which moved the
  refresh-cancel API off `FetcherService`.
- **The Chinese app label stayed rebranded** — upstream added a localized `app_name` of "Handy News
  Reader", which would have relabelled the app on a Chinese-locale device; it now reads 白い熊 Handy RSS
  like every other locale.
- **Reading-view fonts survived upstream's CSS refactor** — the configurable body and heading
  family/size/weight rules were carried onto upstream's reworked stylesheet builder.

---

## Everything this fork adds on top of stock Handy News Reader

The full standing feature set, carried by every release above.

### Identity & packaging
- Installs **side-by-side** with the official app: own `applicationId` (`shiroikuma.handyrss`), own
  content-provider authorities and custom permission, own launcher icon and app label (白い熊 Handy RSS).
- Black/yellow line-art launcher icon at all six densities.
- Self-signed with a stable key, so builds update cleanly over one another.
- `fdroid` flavor (no GMS), correct for a microG device.
- De-branded About screen and issue form; product name purged across all locales.

### Article list & grid
- **Grid article layout** — image-top cards as an alternative to classic list rows.
- **Configurable list layout** — row/grid toggle, columns per row, title line count, and grid image height.
- **Uniform card height** in grid mode, so a taller card is never painted over by the next row.
- **Quick-control top bar** — live sliders for columns, title lines and image height, plus an
  article-title font picker, each row previewing its own typeface.

### Fonts
- **Per-surface fonts** — independent family, weight and size for the article title, the in-list
  article text, and the feed/category drawer.
- **Reading-view body font** — family (via CSS `@font-face`), size and weight, resolved independently
  of the app-wide font.
- **Reading-view heading font** — its own family/weight/size, with h2 tracking 2 pt below h1 to keep
  the hierarchy; falls back to inheriting the body font when unset.
- Fonts may come from the bundled assets or from your own font files on storage.
- Live size-slider previews and a named weight dropdown instead of cryptic numeric fields.

### Theme & chrome
- **Black/yellow chrome across every surface** — window, toolbar, drawer, overflow and popup menus,
  settings screens, dialogs, action icons, FAB, feed-list rows and the label list.
- **Customizable chrome colors** — foreground and background pickers driving toolbars, icons, drawer,
  FAB and dialog text.
- **Content-color pickers** — article background, quote background and rule, subtitle and its border.
- Every toast styled to match the theme.
- All 52 hard-tinted action-icon vectors retinted.

### The 白い熊 Handy RSS UI screen
- One consolidated screen for all fonts and colors, styled with text-wide underlined headings, thin
  section hairlines and a strict indentation ladder.
- **Three entry points** — long-press the drawer settings icon, the top-left home icon, or the article
  list's top-right ⋮.

### Backup, export & import
- **One ZIP holding nine categories** — feeds, articles with their read/starred/scroll state, labels
  and filters, downloaded article text, downloaded images, installed font files, and the settings groups.
  Stock's own backup carries none of the last three, and restores font settings pointing at fonts it
  never saved.
- **Export/Import by category** — a shared export folder with a live "last export" line, and category
  checkboxes behind round pill buttons.
- **Crash-safe writes** — every export goes to a `.part` file and is renamed only once whole, so an
  interrupted backup never leaves a half-written archive that looks real.
- **Thunderbird-native feed OPML** — categories and feeds import into Mozilla Thunderbird as folders
  with individually readable feeds inside.
- **Scheduled auto-backup** to a folder of your choice, on a configurable interval.
- Import still reads every older plain-OPML backup, and can no longer be used to plant a foreign
  automation token or export directory.

### Headless automation
- A **token-gated intent** lets a companion automation app trigger the same export with no UI, choose
  categories and destination, and read back the written path and size.
- **Live progress in real counts** — names the category in progress, files and articles done, and bytes
  written, with a heartbeat so a long silent phase never looks dead.
- **Cancellable from outside**, mid-export, cleanly.
- Off by default, behind a 24-byte random token that never travels inside a backup.
- The export runs in a **foreground service holding a wakelock**, so it survives the screen going off
  instead of being killed part-way with nothing written and nobody told.

### Storage hygiene
- Deleting a feed or a group **reaps the downloaded images and article files** that went with it —
  upstream leaves them behind indefinitely.
- The periodic cleanup **no longer requires the charger**, matching what its own settings checkbox showed.

### Reading
- **Reading-view toolbar** — text size, title size and body font adjustable in place.
- **★ Star always visible** in the reading toolbar rather than buried in the overflow.
- **Articles are marked read on leaving the reading view even when starred.**
- **Pay-or-consent wall bypass** for Mafra sites (lidovky.cz, idnes.cz, expres.cz, antiyoutuber.cz),
  fetched with a search-crawler user agent so the full text loads.

### Fixes
- **Co-install crash fixed** — settings entries carried an explicit intent still targeting the official
  package, which crashed with a permission denial whenever both apps were installed.
- Toolbar/menu icons, compound-button labels and outlier screens recolored where the theme could not reach them.

---

## Earlier fork releases

- **1.1.5+9** (2026-07-28) — export progress that names the running category, keeps a heartbeat through
  the long articles write and reports bytes; the export moved into a foreground service with a wakelock.
- **1.1.5+8** (2026-07-28) — orphaned images and article files reaped on feed and group deletion; the
  delete-old job no longer requires the charger.
- **1.1.5+5** (2026-07-25) — the 保存復元 automation contract: one-ZIP export and a token-gated receiver.
  Base moved to upstream's untagged `1.1.5`.
- **1.1.4+46** (2026-07-24) — the UI screen restyled, and the Export/Import panel with category selection.
- **1.1.4+41** (2026-07-16) — first master-tip rebase; upstream's feed-editor split reconciled with the
  fork's dialog theming.
- **1.1.4+38** (2026-06-27) — first published release.
