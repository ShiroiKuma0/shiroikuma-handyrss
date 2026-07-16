# shiroikuma-handyrss — project context for Claude Code

A personal rebranded fork of [yanus171/Handy-News-Reader](https://github.com/yanus171/Handy-News-Reader) (itself a Flym fork), built and run by the user on a Huawei Mate XT (Android 12+, microG). Co-installs beside the official app as `shiroikuma.handyrss`. Built on Tuxedo OS with OpenJDK 21.

## REQUIRED READING before any change

Before any change on this repo, read **`.claude/skills/handy-rss-build/SKILL.md`** in full. It contains the project identity, the build-test-confirm-push workflow, the full feature history (commits 1–30), and the current open work. The skill's Workflow + Hard rules sections at the top encode lessons learned (build cache, dirty-index recovery, version-bump discipline, on-device verification) — they exist for specific, documented reasons.

## At-a-glance conventions (full detail in the skill)

- **Branch model:** all changes on the **`custom`** branch. `master` mirrors `upstream/master` (yanus171) fast-forward only and never carries our changes. `origin` is `git@github.com:ShiroiKuma0/shiroikuma-handyrss.git` (SSH push).
- **Versioning:** `versionName = 1.1.4+N`, `versionCode = 3390000+N`. **Every build bumps N**, no exceptions. The counter lives at `$HOME/.handyrss_build_no` — outside the repo, never tracked.
- **Reset before applying patches:** `git reset --hard HEAD`, **not** `git checkout -- .`. The latter restores from the index, so a dirty index from an aborted prior push restores an already-patched tree and the next `git apply` fails. `git status --short` should be empty after the reset.
- **Build-test-confirm-push:** changes are tested on the Mate XT before being committed. Once a stage's scope is confirmed, **build automatically** (no per-build "shall I build?" prompt — see the auto-build auto-memory); the build step bumps the counter, builds the APK, copies it to `~/tmp/`, and **auto-delivers via the global `/after-build` skill** (`/adb-check` UNSANDBOXED → `/adb-push` to `/sdcard/tmp/` if a phone is connected, else `/scp` to `skhw:~/tmp/`). Still show the diff before building, and **commit + push only after the user confirms on-device** with "Push" / "good" / "confirmed".

## External state — outside this repo, must be preserved across sessions

- **`$HOME/.handyrss_build_no`** — the build counter. **Currently `41`** (last built version `1.1.4+41`). Never commit.
- **`~/.android-keystores/handyrss-custom.jks`** — signing keystore (alias `handyrss`). Without it, builds cannot be signed.
- **`~/tmp/`** — APK archive directory. Never committed; persists across sessions.
- **`~/git/shiroikuma-handyrss`** — the local working clone (this repo).

## State checkpoint (2026-07-16)

- HEAD = `cb8d9a98` (`README: 1.1.4+41 fork overview + major features`). Last built + on-device-confirmed `1.1.4+41`, counter at `41`. Published as GitHub release `1.1.4+41` (APK attached, default branch `custom`).
- All commits pushed to `origin/custom`. Tree clean.
- **The `custom` stack now sits on `upstream/master` @ `3e885f98` (2026-07-16 master-tip refresh), NOT on the `v1.1.4` tag** — 28 upstream commits pulled in (full-width article images, FB2 poem CSS, anchor back-navigation, network-timeout fixes, WebView file-access hardening, feed-list multiselect delete, edit-feed-list import/export, toast threading fix). Version base stays `1.1.4`; `+N` keeps growing across refreshes. Conflict resolutions are recorded in commit `3275bd00`; notably upstream split `EditFeedsListFragment` into `EditFeedListFragment` + `EditFeedListDragNDropListener` + `GroupActionMode` + `FeedActionCallBack`, and our `Theme.TintDialog` wraps were ported to all six dialog sites there (incl. upstream's new multiselect-delete dialog).
- **Recent feature commits (31–33, `1.1.4+39..+41`; full detail in the skill's feature-commits list):** Mafra pay-or-consent wall bypass via Googlebot UA (lidovky.cz / idnes.cz / expres.cz / antiyoutuber.cz, suffix host match in `Connection.kt`); reading-view **★ Star as an always-visible toolbar action** (was `ifRoom` + a runtime `IF_ROOM` downgrade in `EntryMenu` that pushed it into the overflow); the upstream master-tip rebase itself.
- **Working agreements** (also in auto-memory): build automatically once a stage's scope is confirmed (no "shall I build?" prompt); strict UI-screen indentation discipline; the Thunderbird OPML format + testing gotchas.
- **Open work:**
  1. **D2f shelved** (2026-05-24): app-wide chrome body text (`textColorPrimary`/`Secondary`), settings row/category backgrounds, control accents. The dialog tail is now DONE (commit 26). Revisit only if living with custom chrome colors makes the mismatch nag.
  2. Open question (deferred with D2f): whether to consolidate all colors under the "白い熊 Handy RSS UI" screen.

## Note on the keystore password

The keystore passphrase (`handyrss123`) is currently embedded in the skill's build commands, which means it's public via this repo. The signing keystore file itself is local-only at `~/.android-keystores/handyrss-custom.jks`, so without the keystore the password is useless — practical risk is low for a personal fork. If hygiene matters, move it into a gitignored `keystore.properties` and reference via `-PKEYSTORE_PASSWORD=$(...)`. Otherwise leave as-is.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)
