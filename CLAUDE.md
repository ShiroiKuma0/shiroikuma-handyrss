# shiroikuma-handyrss — project context for Claude Code

A personal rebranded fork of [yanus171/Handy-News-Reader](https://github.com/yanus171/Handy-News-Reader) (itself a Flym fork), built and run by the user on a Huawei Mate XT (Android 12+, microG). Co-installs beside the official app as `shiroikuma.handyrss`. Built on Tuxedo OS with OpenJDK 21.

## REQUIRED READING before any change

Before any change on this repo, read **`.claude/skills/handy-rss-build/SKILL.md`** in full. It contains the project identity, the build-test-confirm-push workflow, the full feature history (commits 1–23), and the current open work. The skill's Workflow + Hard rules sections at the top encode lessons learned (build cache, dirty-index recovery, version-bump discipline, on-device verification) — they exist for specific, documented reasons.

## At-a-glance conventions (full detail in the skill)

- **Branch model:** all changes on the **`custom`** branch. `master` mirrors `upstream/master` (yanus171) fast-forward only and never carries our changes. `origin` is `git@github.com:ShiroiKuma0/shiroikuma-handyrss.git` (SSH push).
- **Versioning:** `versionName = 1.1.4+N`, `versionCode = 3390000+N`. **Every build bumps N**, no exceptions. The counter lives at `$HOME/.handyrss_build_no` — outside the repo, never tracked.
- **Reset before applying patches:** `git reset --hard HEAD`, **not** `git checkout -- .`. The latter restores from the index, so a dirty index from an aborted prior push restores an already-patched tree and the next `git apply` fails. `git status --short` should be empty after the reset.
- **Build-test-confirm-push:** changes are tested on the Mate XT before being committed. The build step bumps the counter, builds the APK, and copies it to `~/tmp/`. **Never deploy to the phone on your own** — after a successful build, stop and ask; only `adb push` after the user explicitly instructs you to. Commit + push is a SEPARATE step again, only run after the user explicitly confirms on-device with "Push" / "good" / "confirmed".

## External state — outside this repo, must be preserved across sessions

- **`$HOME/.handyrss_build_no`** — the build counter. **Currently `21`** (last built version `1.1.4+21`). Never commit.
- **`~/.android-keystores/handyrss-custom.jks`** — signing keystore (alias `handyrss`). Without it, builds cannot be signed.
- **`~/tmp/`** — APK archive directory. Never committed; persists across sessions.
- **`~/git/shiroikuma-handyrss`** — the local working clone (this repo).

## State checkpoint (2026-06-06)

- HEAD = `d105d9d7` (`Skill: post-build deploy gate is an explicit y/n AskUserQuestion prompt`), with the launcher-icon rebrand (`d867fa41`) just below it. Last built + on-device-confirmed `1.1.4+26`, counter at `26`.
- All commits pushed to `origin/custom`. Tree clean.
- **Since the 2026-05-24 checkpoint:** black/yellow line-art launcher icon (yellow-traced coffee cup on a full-bleed black tile, fills black with front-to-back occlusion, ~94% fill so the steam wisp stays visible, all six mipmap densities; rendered from `data/ic_launcher.svg` via a pycairo script — the stale `ic_launcher/web_hi_res_512.png` blue-cross asset is unrelated, ignore it); and the skill's post-build deploy gate is now an explicit y/n `AskUserQuestion` prompt (`adb push` only on Yes / a standing same-turn deploy instruction).
- **Open work** (see the skill's D2 tracker for full context):
  1. Sweep `Theme.TintDialog(dlg)` over the remaining ~10 confirmation dialogs (delete / OPML / label / color / storage / filter / etc.) so they follow chrome too — trivial one-line addition per site.
  2. **D2f shelved** by the user 2026-05-24: app-wide chrome body text (`textColorPrimary`/`Secondary`), settings row backgrounds, control accents. Documented in the skill under D2; revisit only if living with custom chrome colors makes the mismatch nag.
  3. Open question (also deferred with D2f): whether to consolidate all colors under the renamed "UI fonts & colors" settings screen.

## Note on the keystore password

The keystore passphrase (`handyrss123`) is currently embedded in the skill's build commands, which means it's public via this repo. The signing keystore file itself is local-only at `~/.android-keystores/handyrss-custom.jks`, so without the keystore the password is useless — practical risk is low for a personal fork. If hygiene matters, move it into a gitignored `keystore.properties` and reference via `-PKEYSTORE_PASSWORD=$(...)`. Otherwise leave as-is.
