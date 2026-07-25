# shiroikuma-handyrss — project context for Claude Code

A personal rebranded fork of [yanus171/Handy-News-Reader](https://github.com/yanus171/Handy-News-Reader) (itself a Flym fork), built and run by the user on a Huawei Mate XT (Android 12+, microG). Co-installs beside the official app as `shiroikuma.handyrss`. Built on Tuxedo OS with OpenJDK 21.

## REQUIRED READING before any change

Before any change on this repo, read **`.claude/skills/handy-rss-build/SKILL.md`** in full. It contains the project identity, the build-test-confirm-push workflow, the full feature history (commits 1–37), and the current open work. The skill's Workflow + Hard rules sections at the top encode lessons learned (build cache, dirty-index recovery, version-bump discipline, on-device verification) — they exist for specific, documented reasons.

## At-a-glance conventions (full detail in the skill)

- **Branch model:** all changes on the **`custom`** branch. `master` mirrors `upstream/master` (yanus171) fast-forward only and never carries our changes. `origin` is `git@github.com:ShiroiKuma0/shiroikuma-handyrss.git` (SSH push).
- **Versioning:** `versionName = 1.1.5+N`, `versionCode = 3400000+N`. **Every build bumps N**, no exceptions. The counter lives at `$HOME/.handyrss_build_no` — outside the repo, never tracked. It resets to 0 whenever the adopted upstream base version changes.
- **Upstream tracking:** **always rebase to the latest untagged version** (白い熊's standing rule, 2026-07-25) — if `upstream/master` carries a newer `versionName` than ours, adopt it even though no release tag exists. Never wait for the tag.
- **Reset before applying patches:** `git reset --hard HEAD`, **not** `git checkout -- .`. The latter restores from the index, so a dirty index from an aborted prior push restores an already-patched tree and the next `git apply` fails. `git status --short` should be empty after the reset.
- **Build-test-confirm-push:** changes are tested on the Mate XT before being committed. Once a stage's scope is confirmed, **build automatically** (no per-build "shall I build?" prompt — see the auto-build auto-memory); the build step bumps the counter, builds the APK, copies it to `~/tmp/`, and **auto-delivers via the global `/after-build` skill** (`/adb-check` UNSANDBOXED → `/adb-push` to `/sdcard/tmp/` if a phone is connected, else `/scp` to `skhw:~/tmp/`). Still show the diff before building, and **commit + push only after the user confirms on-device** with "Push" / "good" / "confirmed".

## External state — outside this repo, must be preserved across sessions

- **`$HOME/.handyrss_build_no`** — the build counter. **Currently `1`** (last built version `1.1.5+1`; reset to 0 when the base moved `1.1.4` → `1.1.5`). Never commit.
- **`~/.android-keystores/handyrss-custom.jks`** — signing keystore (alias `handyrss`). Without it, builds cannot be signed.
- **`~/tmp/`** — APK archive directory. Never committed; persists across sessions.
- **`~/git/shiroikuma-handyrss`** — the local working clone (this repo).

## State checkpoint (2026-07-25)

- Built `1.1.5+1`, counter at `1`. Delivered to the Mate XT; **awaiting on-device confirmation** — not yet committed or force-pushed.
- **The `custom` stack (54 commits) sits on `upstream/master` @ `9c1c6dd4` (2026-07-25 master-tip refresh; previous refreshes `726fefe3` 2026-07-24 and `3e885f98` 2026-07-16), NOT on any tag** — the newest upstream tag is still `v1.1.4`, but master carries an untagged `1.1.5` / `340` (`02e7ea30`) and **we adopt untagged master versions** per the standing rule, so the base moved `1.1.4`/`3390000` → `1.1.5`/`3400000` and the counter was reset. 20 upstream commits, 15 of them Crowdin churn; the functional two are jsoup `1.17.2` → `1.22.2` and the removal of the `Accept-Encoding: identity` header. Two conflicts, both predicted: the `build.gradle` version block (our stamp kept, upstream's jsoup adopted) and `Connection.kt` vs our consent-wall bypass (upstream's header deletion adopted, our Googlebot-UA + conditional-referer logic kept).
- **Recent feature commits (34–39; full detail in the skill's feature-commits list):** uniform grid-card height; mark entries read on leaving the reading view even when starred; the UI-screen kxkb restyle + category Export/Import panel (`1.1.4+46`); the 2026-07-25 rebase + `1.1.5` base adoption; **long-press the top-right ⋮ in the entry list → 白い熊 Handy RSS UI screen** (`EntriesListFragment.SetupMoreOptionsLongPress`, third entry point beside the drawer settings icon and the top-left actionbar icon).
- Backup branch `custom-pre-master-9c1c6dd4` holds the pre-rebase tip (`f41b9502`); delete it once the new `custom` is force-pushed and confirmed.
- **Working agreements** (also in auto-memory): build automatically once a stage's scope is confirmed (no "shall I build?" prompt); strict UI-screen indentation discipline; the Thunderbird OPML format + testing gotchas.
- **Open work:**
  1. **D2f shelved** (2026-05-24): app-wide chrome body text (`textColorPrimary`/`Secondary`), settings row/category backgrounds, control accents. The dialog tail is now DONE (commit 26). Revisit only if living with custom chrome colors makes the mismatch nag.
  2. Open question (deferred with D2f): whether to consolidate all colors under the "白い熊 Handy RSS UI" screen.

## Note on the keystore password

The keystore passphrase (`handyrss123`) is currently embedded in the skill's build commands, which means it's public via this repo. The signing keystore file itself is local-only at `~/.android-keystores/handyrss-custom.jks`, so without the keystore the password is useless — practical risk is low for a personal fork. If hygiene matters, move it into a gitignored `keystore.properties` and reference via `-PKEYSTORE_PASSWORD=$(...)`. Otherwise leave as-is.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)
