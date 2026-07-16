---
name: upstream-new-version
description: Check yanus171/Handy-News-Reader for new upstream work and pull it into our fork by rebasing the whole `custom` feature stack — onto the new release tag when there is one (adopting upstream's version and resetting the build counter), or, when there is no new tag but upstream/master has advanced, onto the latest master tip (keeping our version base unchanged so our +N numbering keeps growing). Before every rebase it shows a descriptive table of the new upstream functionality since our last base and waits for an OK. It fast-forwards the `master` mirror, backs up `custom`, reconciles conflicts — automatically when small, stopping to discuss and plan when significant — then builds the APK via the handy-rss-build skill and delivers it automatically via the global /after-build skill (no transfer prompt); the force-push waits for on-device confirmation. Use this skill when the user runs /upstream-new-version, or asks to pull/update to new Handy News Reader (Handy-News-Reader / handyrss / FlymFork / feedexfork) upstream work, rebase onto a new release or the latest master, or "check upstream for a new version". This is the upstream-update front-end for the handy-rss-build fork; it delegates the actual build to handy-rss-build.
---

Base directory for this skill: /home/shiroikuma/git/shiroikuma-handyrss/.claude/skills/upstream-new-version

# Handy RSS — upstream new-version rebase + rebuild

This skill automates the **"new upstream work landed, pull it into our fork"** flow for the
user's rebranded Handy News Reader fork (`shiroikuma.handyrss` / `白い熊 Handy RSS`) — whether that work
is a new release tag or just fresh commits on `upstream/master` (see **Two update modes**). It is the
front-end to the **`handy-rss-build`** skill: it does the git work (detect new upstream work → mirror →
rebase the `custom` stack → reconcile) and then hands the **build** off to `handy-rss-build`.

**READ the `handy-rss-build` skill first** (`.claude/skills/handy-rss-build/SKILL.md`) — it is the
authoritative source for the fork's identity, the 5 rebrand edits, the feature-commit stack, the
versioning scheme, the build commands + NOISE regex, and the build/phone/push gates. This skill does
not duplicate those; it references them. Where the two ever disagree, `handy-rss-build` wins for
build mechanics and this skill wins for the rebase flow.

## What this skill does (and does not do)

Does: fetch upstream → decide what to rebase onto (see **Two update modes** below) → **show a
descriptive table of the new upstream functionality since our last base and wait for an OK** →
fast-forward `master`, back up `custom`, rebase the whole `custom` feature stack onto the target,
reconcile conflicts (auto if small, **discuss-and-plan with the user if significant**), handle the
version/counter per the mode, verify the rebrand + features survived, then build via `handy-rss-build`.

Does NOT: force-push `custom` or commit the doc/version updates without telling you. The build IS
delivered to the phone automatically (via the global `/after-build` skill — no transfer prompt), but
the force-push happens only **after the user has tested the new build on-device and confirmed** — same
build-test-confirm-push discipline as `handy-rss-build` (see its Hard rules; the git-push hold applies
here too).

## Two update modes

Upstream work arrives in two shapes; this skill handles both, and the mode drives the rebase target
and the version handling:

- **Release bump** — a new release **tag** (newer, by date, than the tag our stack sits on). Rebase the
  stack onto the tag, **adopt upstream's new version** (`versionName`/`versionCode` base), and **reset
  the build counter to 0** so the first new build is `+1`. Propagate the new base literals into the docs.
  (Phases 6–7 apply.)
- **Master-tip refresh** — **no** new tag, but `upstream/master` has advanced past our base. Rebase the
  stack onto the **latest `upstream/master` tip** (all its commits). **Keep our version base exactly as
  is** (still e.g. `1.1.4`) and **do NOT reset the counter** — our `+N` numbering just keeps growing (so
  the next build is the current counter `+1`). No base-literal propagation. (Phases 6–7 are skipped.)

Priority: if there **is** a new release tag, take the release-bump mode (rebase onto the tag, not the
even-newer untagged commits beyond it — those wait for their own release). Only when there is no new tag
do we target the master tip. If there is neither a new tag nor any master advance past our base, there
is nothing to do — STOP.

## Key facts (from handy-rss-build — re-verify, don't trust blindly)

| Item | Value |
|------|-------|
| Upstream remote | `upstream` → `https://github.com/yanus171/Handy-News-Reader.git` (HTTPS, fetch) |
| Origin remote | `origin` → `git@github.com:ShiroiKuma0/shiroikuma-handyrss.git` (SSH, push) |
| Mirror branch | `master` — fast-forward only, never carries our changes |
| Customization branch | `custom` — a **stack** of feature commits on top of the rebrand, rebased onto each release tag |
| Current base (as of this skill's writing) | upstream `v1.1.4`, versionName base `1.1.4`, versionCode base `3390000` (= upstream `339` × 10000) |
| Counter (external) | `$HOME/.handyrss_build_no` — outside the repo; **reset to 0 only on a release bump** (new tag) so the first new build is `+1`; on a **master-tip refresh** it is left as-is and `+N` keeps growing |
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

### Phase 1 — Detect what's new (tag or master-tip)

1. **Our current base commit** — the commit the stack sits on, whether that's a tagged release or a
   previous master tip: `OLD_BASE=$(git merge-base custom upstream/master)`. (This is robust across
   mixed histories: after a master-tip refresh our base is a plain master SHA, not a tag.) Cross-check:
   the committed base versionName in `FlymFork/build.gradle` (strip `+N`) names the release we *adopted*
   (e.g. `1.1.4`); its tag `v1.1.4` should be an ancestor of `custom`.
2. **Newest upstream release tag by date** — exclude our OWN snapshot tags (`shiroikuma-v*`):
   `NEW_TAG=$(git for-each-ref --sort=creatordate --format='%(refname:short)' refs/tags | grep -E '^v[0-9]' | grep -v '^shiroikuma-' | tail -1)`
   Sanity-check against the dated list (`… refs/tags | tail`): it must be a recent `FlymFork`-layout
   release, not a 2016 Flym tag (`v1.8.0`/`v1.9.4` sort high lexically but are old by date), and not one
   of ours. **Pick by date, never lexically.**
3. **Decide the mode + target** (see *Two update modes*):
   - **New release tag?** If `NEW_TAG` is newer than our base — i.e. `NEW_TAG` is NOT an ancestor of
     `OLD_BASE` (`git merge-base --is-ancestor <NEW_TAG> $OLD_BASE` is false) → **release bump**:
     `MODE=release`, `REBASE_TARGET=<NEW_TAG>`.
   - **Else, has `upstream/master` advanced past our base?** If `git rev-list --count $OLD_BASE..upstream/master`
     is > 0 → **master-tip refresh**: `MODE=refresh`, `REBASE_TARGET=upstream/master`.
   - **Else nothing to do** — report "already up to date with upstream (base `<version>`; no new tag and
     no new master commits)" and STOP. No destructive ops.
4. Confirm `master` can fast-forward: `git merge-base --is-ancestor master upstream/master` (it should —
   `master` carries none of our work). If not, upstream rewrote history → STOP and discuss.

### Phase 2 — New-functionality table + OK-gate (STOP — before every rebase)

**This gate fires before every rebase, both modes.** Just before touching any branch, render a
**descriptive table of the new upstream functionality** we are about to pull in — the commits in
`$OLD_BASE..$REBASE_TARGET` — and get the user's OK. Don't dump a raw `git log`; make it readable:
```
git log --no-merges --format='%h%x09%s' $OLD_BASE..$REBASE_TARGET
```
Present it as a table, newest first, with columns: **Commit** (`%h`), **Type** (feat / fix / refactor /
chore / UI, inferred from the subject), and **What it does** in plain language. **Flag the commits that
touch files our feature stack owns** (`EntriesListFragment`, `EntriesCursorAdapter`, `WebEntryContent`,
`EntryFragment`, `Theme.java`, `DrawerAdapter`, the font prefs, the entry/list layouts,
`res/xml/general_preferences.xml`, `res/values/{themes,styles,colors}.xml`) — those are the likely
conflict sites, so the user sees the risk before saying go. (A quick way to spot them:
`git log --stat $OLD_BASE..$REBASE_TARGET` and eyeball the paths.)

Also report, in the same summary:
- the **mode** (release bump onto `NEW_TAG` vs master-tip refresh onto `upstream/master`) and what it
  means for the version (new base + counter reset to `+1`, vs version unchanged + `+N` keeps growing);
- the **stack size** — `OLD_COUNT=$(git rev-list --count $OLD_BASE..custom)` — so Phase 8 can confirm no
  commit was silently dropped;
- the plan: FF `master`, back up `custom`, rebase the stack onto `$REBASE_TARGET`.

**Proceed only on the user's explicit OK.**

### Phase 3 — Fast-forward the mirror + back up custom

1. `git checkout master && git merge --ff-only upstream/master` (FF only; if it can't FF, upstream
   rewrote history — STOP and discuss).
2. **Safety backup of the stack** (recovery is then trivial): back up `custom` under a stable label —
   `custom-pre-<NEW_TAG>` (e.g. `custom-pre-v1.2.0`) for a release bump, or `custom-pre-master-<shortsha>`
   (the `$REBASE_TARGET` short SHA) for a master-tip refresh, since a refresh has no tag and there may be
   several over time. `git branch <backup-name> custom`. Don't put a date in the name (timestamps aren't
   available); the tag/SHA is the stable label. `origin/custom` + the reflog are additional safety nets.

### Phase 4 — Rebase the custom stack onto the target

Only after the Phase 2 table-gate OK:
```
git checkout custom
git rebase --onto <REBASE_TARGET> $OLD_BASE custom
```
(`$OLD_BASE` from Phase 1; `<REBASE_TARGET>` = the new tag for a release bump, or `upstream/master` for a
refresh.) This replays the whole feature stack (rebrand + all features) onto the target. Three outcomes:
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

### Phase 6 — Version base + counter (mode-dependent)

**Master-tip refresh (`MODE=refresh`): SKIP this phase.** Keep our version base exactly as it is — do
NOT touch `build.gradle`'s `versionName`/`versionCode`, and do NOT reset the counter. The
`handy-rss-build` build step bumps the counter as usual, so the next build is the current counter `+1`
(e.g. `1.1.4+40`); our `+N` numbering just keeps growing across refreshes. If the rebase surfaced a
conflict on the `versionName`/`versionCode` line because upstream changed it on master, **resolve in
favour of our current base** (keep `1.1.4+N`) — we only adopt upstream's version on a real release,
never from an untagged master tip.

**Release bump (`MODE=release`): reset the base + counter.** Read the **pristine upstream** version from
the new tag (NOT from our rebased build.gradle, which still carries our `+N` line):
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

### Phase 7 — Propagate the new base into the docs (release bump only)

**Skip for a master-tip refresh** — the base literals don't change, so there's nothing to propagate.

On a **release bump**, the base literals `1.1.4` and `3390000` / `339` are **hard-coded in several
places** that the build relies on. If you don't update them, the next build stamps the OLD base. Update
the ACTIVE base literals
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
Also sanity-check the feature stack is all there: `git rev-list --count $REBASE_TARGET..custom` should
equal `OLD_COUNT` captured in Phase 2 (a smaller number means a commit was dropped during the rebase —
STOP and investigate before building); skim `git log --oneline $REBASE_TARGET..custom` to eyeball the
features.

### Phase 9 — Build, test, confirm, push (delegate to handy-rss-build)

1. **Invoke the `handy-rss-build` skill** to build `:FlymFork:assembleFdroidRelease`. It bumps the
   counter and stamps the version — for a **release bump** `<NEW_VN>+1` / `<NEW_VC_BASE>+1` (counter
   0 → 1); for a **master-tip refresh** the unchanged base `+<next N>` (counter keeps growing, e.g.
   `1.1.4+40`) — filters output via the NOISE regex, and copies the APK to `~/tmp/`. Use its canonical
   command + flags verbatim — do not re-derive them here.
2. **Deliver automatically.** After a successful build, invoke the global `/after-build` skill — it
   `/adb-check`s UNSANDBOXED, then `/adb-push`es to the phone if connected, else `/scp`s to `skhw`,
   announcing what landed. No transfer prompt (handy-rss-build "Deliver" rule).
3. **User tests on-device.** A new-upstream build deserves a real smoke test: launch, open an article
   (reading view), check the list/grid, fonts, and chrome colors all survived the rebase.
4. **Only after the user confirms on-device** ("Push" / "good" / "confirmed"):
   - Stage **explicitly, NOT `git add -A`** — the working tree carries unrelated untracked files
     (`.bashrc`, `.claude/…`, etc.) that must never be committed. Add just the version stamp and any
     Phase 6–7 doc edits: `git add FlymFork/build.gradle` plus, on a release bump,
     `.claude/skills/handy-rss-build/SKILL.md .claude/skills/upstream-new-version/SKILL.md CLAUDE.md`.
     Then commit (the rebased stack tip already carries the features; this commit records the last-built
     version stamp — for a refresh, e.g. "Rebase onto upstream master @ `<shortsha>`; build `1.1.4+N`").
     Because the rebase rewrote history, publishing `custom` is a **force-push**: `git push -f origin custom`.
   - Then sync: `git fetch origin --tags && git pull --rebase origin custom`.
   - **Release bump only** — optional snapshot tag: `git tag shiroikuma-v<NEW_VN> && git push origin shiroikuma-v<NEW_VN>`.
     (A refresh keeps the same version, so no new snapshot tag.)
   - Once the new `custom` is confirmed pushed, the `<backup-name>` branch (from Phase 3) can be deleted
     (ask first): `git branch -D <backup-name>`.

## Hard rules

- **Deliver automatically; never force-push on your own.** Build + local `~/tmp/` copy is fine after
  the build gate, and delivery to the phone is automatic via `/after-build` (no prompt); only
  `git push -f origin custom` waits for on-device confirmation. (Inherits handy-rss-build's gates.)
- **Never recreate `custom` from scratch.** It's a feature stack — rebase it; if it won't rebase
  cleanly, that's a "significant conflict" to discuss, not a reason to rebuild from the rebrand seds.
- **Show the new-functionality table and get an OK before every rebase.** Both modes: render the
  descriptive table of the `$OLD_BASE..$REBASE_TARGET` commits (Commit / Type / What it does, flagging
  the ones that touch files we own) and proceed only on the user's explicit OK (Phase 2).
- **No new tag but `upstream/master` moved ⇒ master-tip refresh, NOT "nothing to do".** Rebase onto
  `upstream/master`, keep the version base, let `+N` keep growing. Only STOP as "up to date" when there
  is neither a new tag nor any master advance past our base. (When there IS a new tag, prefer it — rebase
  onto the tag, not the untagged commits beyond it.)
- **Back up before rebasing** (`custom-pre-<NEW_TAG>` for a release bump, `custom-pre-master-<shortsha>`
  for a refresh), and don't delete the backup until the new `custom` is pushed and confirmed.
- **`master` is FF-only.** If it can't fast-forward, stop — upstream rewrote history; that's a
  conversation, not a `--force`.
- **Significant conflict ⇒ plan with the user first.** Don't improvise large reconciliations silently.
  Surface the conflicting commits + options via `AskUserQuestion` and act on the choice.
- **Counter resets to 0 only on a release bump** (new tag), so the first new-release build is `+1`. On a
  **master-tip refresh the counter is left as-is** and `+N` keeps growing. Every build still bumps it,
  failed ones included; never reuse an `+N`.
- **Propagate the new base literals** (release bump only) into build.gradle + both docs, or the next
  build stamps the old base. A refresh leaves the base unchanged — nothing to propagate. Don't rewrite
  historical feature-commit version mentions.
- **Pick the newest tag by date, never lexically** (the 2016 Flym tags are a trap).

## Recovery

- Mid-rebase, to bail: `git rebase --abort` (restores `custom` to its pre-rebase tip).
- After a finished-but-wrong rebase: `git reset --hard <backup-name>` (the Phase 3 backup branch —
  `custom-pre-<NEW_TAG>` or `custom-pre-master-<shortsha>`) (or `git reset --hard origin/custom` if not
  yet force-pushed) puts `custom` back.
- `master` FF is always safe to keep; it carries none of our work.
- The build counter lives outside the repo, so git resets don't touch it — if you reset after a build,
  the counter has still advanced (correct; never reuse a number).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
