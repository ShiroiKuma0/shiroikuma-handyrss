---
name: upstream-new-version
description: Check yanus171/Handy-News-Reader for a new upstream release and, if there is one, fast-forward the `master` mirror and rebase the whole `custom` feature stack onto the new tag, reconciling conflicts — automatically when they are small, but stopping to discuss and plan with the user when they are significant — then reset the version base + counter and build the new APK by invoking the handy-rss-build skill (never auto-deploying). Use this skill when the user runs /upstream-new-version, or asks to pull/update to a new Handy News Reader (Handy-News-Reader / handyrss / FlymFork / feedexfork) upstream version, rebase onto a new release tag, or "check upstream for a new version". This is the upstream-update front-end for the handy-rss-build fork; it delegates the actual build to handy-rss-build.
---

Base directory for this skill: /home/shiroikuma/git/shiroikuma-handyrss/.claude/skills/upstream-new-version

# Handy RSS — upstream new-version rebase + rebuild

This skill automates the **"a new upstream release came out, pull it into our fork"** flow for the
user's rebranded Handy News Reader fork (`shiroikuma.handyrss` / `白い熊 Handy RSS`). It is the
front-end to the **`handy-rss-build`** skill: it does the git work (detect new release → mirror →
rebase the `custom` stack → reconcile) and then hands the **build** off to `handy-rss-build`.

**READ the `handy-rss-build` skill first** (`.claude/skills/handy-rss-build/SKILL.md`) — it is the
authoritative source for the fork's identity, the 5 rebrand edits, the feature-commit stack, the
versioning scheme, the build commands + NOISE regex, and the build/phone/push gates. This skill does
not duplicate those; it references them. Where the two ever disagree, `handy-rss-build` wins for
build mechanics and this skill wins for the rebase flow.

## What this skill does (and does not do)

Does: fetch upstream → find the newest **release tag by date** → if it's newer than our current base,
fast-forward `master`, back up `custom`, rebase the whole `custom` feature stack onto the new tag,
reconcile conflicts (auto if small, **discuss-and-plan with the user if significant**), reset the
version base + build counter, propagate the new base version into the project + skill docs, verify the
rebrand + features survived, then build via `handy-rss-build`.

Does NOT: deploy to the phone, force-push `custom`, or commit the doc-literal updates without telling
you. Deployment and the force-push happen only **after the user has tested the new build on-device and
confirmed** — same build-test-confirm-push discipline as `handy-rss-build` (see its Hard rules; the
"never deploy on your own / `adb push` only on explicit instruction" rule applies here too).

## Key facts (from handy-rss-build — re-verify, don't trust blindly)

| Item | Value |
|------|-------|
| Upstream remote | `upstream` → `https://github.com/yanus171/Handy-News-Reader.git` (HTTPS, fetch) |
| Origin remote | `origin` → `git@github.com:ShiroiKuma0/shiroikuma-handyrss.git` (SSH, push) |
| Mirror branch | `master` — fast-forward only, never carries our changes |
| Customization branch | `custom` — a **stack** of feature commits on top of the rebrand, rebased onto each release tag |
| Current base (as of this skill's writing) | upstream `v1.1.4`, versionName base `1.1.4`, versionCode base `3390000` (= upstream `339` × 10000) |
| Counter (external) | `$HOME/.handyrss_build_no` — outside the repo; reset on an upstream bump so the first new build is `+1` |
| App module | `FlymFork` |
| Build task (delegated) | `handy-rss-build` → `:FlymFork:assembleFdroidRelease` |

**Tag-by-date caveat (critical):** the upstream repo carries ancient 2015-2016 Flym-era tags
(`v1.8.0`, `v1.9.4`, …) that sort to the TOP lexically but are obsolete `mobile`/`wear`-layout code.
ALWAYS pick the newest tag by **creation date**, never by lexical/version sort:
```
git for-each-ref --sort=creatordate --format='%(creatordate:short) %(refname:short)' refs/tags | tail
```
The current `FlymFork`-layout releases are `v0.18.x`, `v0.19.x`, `v1.0.x`, `v1.1.x`.

**`custom` is a feature stack, NOT one commit.** The `handy-rss-build` "Update procedure" has a
fallback that *recreates `custom` from the tag and re-applies the four rebrand seds*. That fallback is
ONLY valid for the original single-commit rebrand. Today `custom` is a couple dozen commits (rebrand +
grid/fonts/colors/dialogs/etc.) and grows over time. **Never** recreate `custom` from scratch here — it would silently destroy every
feature commit. Always rebase the whole stack; if it can't be rebased cleanly, that's a "significant
conflict" → discuss with the user (see triage below).

## Workflow

Run the phases in order. Echo what you find at each gate; stop at the named STOP points.

### Phase 0 — Preflight & bootstrap

1. `cd ~/git/shiroikuma-handyrss`. Confirm a clean tree: `git status --porcelain` must be empty. If
   not, STOP and surface it (don't `reset --hard` someone's uncommitted work without asking).
2. Note the current branch; remember to return to `custom` at the end.
3. **Fetch upstream + tags:** `git fetch upstream --tags` and `git fetch origin --tags`.
   This clone may not have `master`, `upstream/*` tracking refs, or tags yet — fetching creates them.
4. **Ensure the `master` mirror exists:** if `git rev-parse --verify -q master` fails, create it:
   `git branch master upstream/master` (or `git checkout -B master upstream/master`).
5. **Ensure `custom` matches `origin/custom`** (so the rebase starts from the published tip):
   `git rev-parse custom` should equal `git rev-parse origin/custom`. If they differ, STOP and reconcile
   first (the user may have local-only work).

### Phase 1 — Detect a new release

1. Newest upstream tag **by date** — and exclude our OWN snapshot tags (`shiroikuma-v*`), which are the
   newest refs of all and would otherwise win the date sort:
   `NEW_TAG=$(git for-each-ref --sort=creatordate --format='%(refname:short)' refs/tags | grep -E '^v[0-9]' | grep -v '^shiroikuma-' | tail -1)`
   (the `^v[0-9]` filter already drops `shiroikuma-v…`, but keep the explicit `grep -v` as a guard if the
   snapshot naming ever changes.) Sanity-check against the dated list — e.g. as of this writing the newest
   ref overall is `shiroikuma-v1.1.4` (2026-05-22, OURS); the newest *upstream* release is `v1.1.4`
   (2026-03-29). Eyeball that the pick is a recent `FlymFork`-layout release, not a 2016 Flym tag, and
   not one of ours.
2. Current base tag = the upstream tag our stack sits on. Derive it from the committed base versionName
   in `FlymFork/build.gradle` (strip the `+N`): base `1.1.4` → `OLD_TAG=v1.1.4`. Verify `OLD_TAG`
   exists and is an ancestor of `custom` (`git merge-base --is-ancestor v1.1.4 custom`).
3. **Compare.** If `NEW_TAG == OLD_TAG` (or `NEW_TAG` is not newer than `OLD_TAG`): **report "already on
   the latest upstream release (vX.Y.Z), nothing to do"** and STOP — no destructive ops.
   - Edge case: `upstream/master` has commits but no new tag → there's no new *release* to rebase onto.
     Mention it, but do not rebase onto an untagged commit unless the user explicitly asks.

### Phase 2 — Confirm before destructive ops (STOP/gate)

Summarize for the user and get a go-ahead before touching branches:
- old base `OLD_TAG` → new release `NEW_TAG` (+ its date);
- **capture the stack size now** — `OLD_COUNT=$(git rev-list --count OLD_TAG..custom)` — and report it
  (Phase 8 compares against it to confirm no commits were silently dropped in the rebase);
- the plan: FF `master`, back up `custom`, rebase the stack onto `NEW_TAG`.

Proceed only on the user's go-ahead.

### Phase 3 — Fast-forward the mirror + back up custom

1. `git checkout master && git merge --ff-only upstream/master` (FF only; if it can't FF, upstream
   rewrote history — STOP and discuss).
2. **Safety backup of the stack** (recovery is then trivial): `git branch custom-pre-<NEW_TAG> custom`
   (e.g. `custom-pre-v1.2.0`). Don't use a date in the name (timestamps aren't available); the version
   is the stable label. `origin/custom` + the reflog are additional safety nets.

### Phase 4 — Rebase the custom stack onto the new tag

```
git checkout custom
git rebase --onto <NEW_TAG> <OLD_TAG> custom
```
This replays the whole feature stack (rebrand + all features) onto the new release. Three outcomes:
- **Clean** → go to Phase 6.
- **Conflicts that are small** → resolve inline (Phase 5 → "small"), `git rebase --continue`.
- **Conflicts that are significant** → Phase 5 → "significant": pause, plan with the user.

### Phase 5 — Conflict triage (the heart of this skill)

For each conflicting commit, judge magnitude. Use the canonical definitions in `handy-rss-build` to know
the *intended end state* of every edit: the 5 rebrand edits (applicationId, `PACKAGE_NAME`, manifest
provider authorities, `app_name` label, APK naming) and the feature-commit descriptions (grid layout,
configurable list/grid, per-surface fonts, reading-view CSS fonts, drawer colors, menu/settings/window
recolor, chrome black/yellow pass, co-install intent fix, grid sliders, reading-view toolbar, settings
rename, content-color pickers, chrome-color D2a–D2e, dialog TintDialog). When in doubt about what a
hunk *should* become, that skill's per-commit notes are the spec.

**SMALL — resolve automatically, then continue:**
- The conflict is only context drift (surrounding lines moved) around one of our edits, and the correct
  resolution is obvious from the canonical edit.
- One of the 5 rebrand lines moved; re-apply the rebrand intent (the seds in `handy-rss-build` are the
  definition).
- Whitespace / import-ordering / adjacent-but-independent edits.
- Heuristic: ≤ ~2 commits conflict, every conflict is an obvious re-application of a known edit, no
  upstream rename/delete/refactor of a file we own, and nothing semantic (no API we call changed shape).

**SIGNIFICANT — STOP, do not improvise, discuss & plan with the user:**
- Upstream **refactored / renamed / deleted** a file central to our features:
  `EntriesListFragment`, `EntriesCursorAdapter`, `WebEntryContent`, `WebViewExtended`, `EntryFragment`,
  `Theme.java`, `DrawerAdapter`, `FontSelectPreference` / `FontSizePreference`,
  `res/xml/general_preferences.xml`, `res/values/{themes,styles,colors}.xml`, the entry/list layouts,
  or the `AndroidManifest` provider blocks.
- A **semantic** conflict: our code calls a method/field upstream changed or removed (compiles only
  after non-trivial rework) — git may even auto-merge the text but the result won't build.
- Many commits conflict (> ~2–3), or the same file conflicts across several commits.
- The shape of a rebrand edit site changed (e.g. `PACKAGE_NAME` became `BuildConfig.APPLICATION_ID` —
  which would make rebrand edit 2 unnecessary and let edit 3 use `${applicationId}`; that's a real
  decision, not a silent fix).

**When significant:** leave the rebase paused (do NOT `--abort` reflexively — the partial state is
informative), gather the facts, and bring the user a concrete plan via `AskUserQuestion`:
- which commit(s) conflict and why (file refactored / API changed / feature obsoleted);
- options, e.g.: (a) resolve together hunk-by-hunk now; (b) drop/replace a feature upstream made
  obsolete or incompatible; (c) re-implement a feature fresh on the new base; (d) abort the rebase and
  defer the whole update. Recommend one. Only act on the user's choice.
- If the user wants to bail entirely: `git rebase --abort`, then `git checkout custom` is already
  intact (the abort restores it); `master` stays fast-forwarded (harmless); delete or keep the backup
  branch as they prefer.

After every resolution, before `--continue`, make sure the working tree reflects the *intended* edit
(not just "no conflict markers"). When the whole rebase finishes, the stack tip is the new `custom`.

### Phase 6 — Reset the version base + counter

Read the **pristine upstream** version from the new tag (NOT from our rebased build.gradle, which still
carries our `+N` line):
```
git show <NEW_TAG>:FlymFork/build.gradle | grep -E 'versionName|versionCode'
```
Let `NEW_VN` = upstream versionName (e.g. `1.2.0`), `NEW_VC_BASE` = upstream versionCode × 10000
(e.g. upstream `340` → `3400000`).

1. **Counter reset** so the first build of the new upstream is `+1`:
   `echo 0 > $HOME/.handyrss_build_no` (prefer explicit `0` over `rm -f` — the build step does
   `N=$(cat …)` and a missing file would error).
2. **build.gradle** `defaultConfig`: set `versionName '<NEW_VN>+0'` and `versionCode <NEW_VC_BASE>` as a
   valid placeholder. (The `handy-rss-build` build step owns the real stamp — it bumps the counter to 1
   and writes `<NEW_VN>+1` / `<NEW_VC_BASE>+1` at build time. Leave the derived `applicationVariants`
   `def versionName/versionCode` lines untouched.)

### Phase 7 — Propagate the new base into the docs (so the next build stamps correctly)

The base literals `1.1.4` and `3390000` / `339` are **hard-coded in several places** that the build
relies on. If you don't update them, the next build stamps the OLD base. Update the ACTIVE base literals
(NOT the historical feature-commit version tags, which record what shipped and must stay):
- **`.claude/skills/handy-rss-build/SKILL.md`** — the Versioning section, the Build "Version stamp"
  paragraph, the gradle/`sed` base literals, and the "Update procedure" base literals (`1.1.4` →
  `<NEW_VN>`, `3390000` → `<NEW_VC_BASE>`, `339` → upstream's new versionCode). Update its project-
  identity table's "latest upstream release" too.
- **`CLAUDE.md`** — the at-a-glance Versioning line (`1.1.4+N` / `3390000+N`) and the State-checkpoint
  block (HEAD, last-built version, counter).
- Do a final `grep -rn '1\.1\.4\|3390000\|\b339\b'` over both docs to catch stragglers; eyeball each —
  keep historical mentions, change active base literals.

(These doc edits are bookkeeping; they may be committed with the rebased stack or in a follow-up commit,
but — like everything else — only **pushed** after the on-device confirmation in Phase 9.)

### Phase 8 — Verify the rebrand + features survived

Re-check the 5 rebrand edits are present on the rebased tree (per `handy-rss-build`'s verification list):
- `FlymFork/build.gradle`: `applicationId 'shiroikuma.handyrss'` AND `namespace 'ru.yanus171.feedexfork'`
  still intact (namespace must NOT have changed).
- `FeedData.java`: `PACKAGE_NAME = "shiroikuma.handyrss"`.
- `AndroidManifest.xml`: the three authorities `shiroikuma.handyrss.provider.FeedData` /
  `.fileprovider` / `.provider.WRITE_PERMISSION`.
- `res/values/strings.xml`: `白い熊 Handy RSS`.
Also sanity-check the feature stack is all there: `git rev-list --count <NEW_TAG>..custom` should equal
`OLD_COUNT` captured in Phase 2 (a smaller number means a commit was dropped during the rebase — STOP
and investigate before building); skim `git log --oneline <NEW_TAG>..custom` to eyeball the features.

### Phase 9 — Build, test, confirm, push (delegate to handy-rss-build)

1. **Invoke the `handy-rss-build` skill** to build `:FlymFork:assembleFdroidRelease`. It bumps the
   counter (0 → 1), stamps `<NEW_VN>+1` / `<NEW_VC_BASE>+1`, filters output via the NOISE regex, and
   copies the APK to `~/tmp/`. Use its canonical command + flags verbatim — do not re-derive them here.
2. **Do NOT deploy on your own.** After a successful build, STOP and ask whether to `adb push` to the
   phone; push only when the user explicitly says so (handy-rss-build Hard rule).
3. **User tests on-device.** A new-upstream build deserves a real smoke test: launch, open an article
   (reading view), check the list/grid, fonts, and chrome colors all survived the rebase.
4. **Only after the user confirms on-device** ("Push" / "good" / "confirmed"):
   - `git add -A` and commit (the rebased stack tip already carries the features; commit any Phase 6-7
     doc/version edits). Because the rebase rewrote history, publishing `custom` is a **force-push**:
     `git push -f origin custom`.
   - Then sync: `git fetch origin --tags && git pull --rebase origin custom`.
   - Optional snapshot tag: `git tag shiroikuma-v<NEW_VN> && git push origin shiroikuma-v<NEW_VN>`.
   - Once the new `custom` is confirmed pushed, the `custom-pre-<NEW_TAG>` backup branch can be deleted
     (ask first): `git branch -D custom-pre-<NEW_TAG>`.

## Hard rules

- **Never deploy or force-push on your own.** Build + local `~/tmp/` copy is fine after the build gate;
  `adb push` waits for explicit instruction, and `git push -f origin custom` waits for on-device
  confirmation. (Inherits handy-rss-build's gates.)
- **Never recreate `custom` from scratch.** It's a feature stack — rebase it; if it won't rebase
  cleanly, that's a "significant conflict" to discuss, not a reason to rebuild from the rebrand seds.
- **Back up before rebasing** (`custom-pre-<NEW_TAG>`), and don't delete the backup until the new
  `custom` is pushed and confirmed.
- **`master` is FF-only.** If it can't fast-forward, stop — upstream rewrote history; that's a
  conversation, not a `--force`.
- **Significant conflict ⇒ plan with the user first.** Don't improvise large reconciliations silently.
  Surface the conflicting commits + options via `AskUserQuestion` and act on the choice.
- **Counter resets to 0 on an upstream bump**, so the first build is `+1`. Every build still bumps it,
  failed ones included; never reuse an `+N`.
- **Propagate the new base literals** into build.gradle + both docs, or the next build stamps the old
  base. Don't rewrite historical feature-commit version mentions.
- **Pick the newest tag by date, never lexically** (the 2016 Flym tags are a trap).

## Recovery

- Mid-rebase, to bail: `git rebase --abort` (restores `custom` to its pre-rebase tip).
- After a finished-but-wrong rebase: `git reset --hard custom-pre-<NEW_TAG>` (or `git reset --hard
  origin/custom` if not yet force-pushed) puts `custom` back.
- `master` FF is always safe to keep; it carries none of our work.
- The build counter lives outside the repo, so git resets don't touch it — if you reset after a build,
  the counter has still advanced (correct; never reuse a number).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
