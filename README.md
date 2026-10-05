# Stacked PRs for GitHub — JetBrains plugin

A WebStorm (and any JetBrains IDE) UI for GitHub stacked pull requests, built on GitHub's
[`gh stack`](https://github.com/github/gh-stack) CLI extension. Every change goes through the CLI;
the plugin only reads its state files.

*Not affiliated with or endorsed by GitHub. GitHub is a trademark of GitHub, Inc.*

## Requirements

- [GitHub CLI](https://cli.github.com), logged in (`gh auth login`)
- The extension: `gh extension install github/gh-stack` (tested with 0.1.x)
- WebStorm or another IntelliJ-based IDE, 2026.2+

## Features

- **Stacked PRs tool window** (right side): the checked-out branch's stack as a tree, top → bottom, with PR number
  and status (`✓` merged, `◎` queued, `⚠` needs rebase, `○` open, `◌` not submitted). Double-click a
  branch to switch to it; right-click for insert, remove, layer diff, rebase-from-here, open PR.
- **Stack switcher**: the `⧉ api 2/4` dropdown next to the Git branch widget, the status bar widget,
  or `⌘⌥K L` / `Ctrl+Alt+X L`. Lists the current stack's branches and its trunk; click to switch.
- **Checks**: click a branch's checks label for its failing, running and cancelled checks, each linking
  to its job on GitHub, with **Re-run failed** once the workflow run has finished.
- **Stacks from GitHub**: check out a branch that no local stack tracks (a colleague's stack, or yours
  from another machine) and, if its pull request is in a stack on GitHub, that stack is pulled in with
  `gh stack checkout`. Turn it off in Settings ▸ Tools ▸ Stacked PRs.
- **Insert branch below/above**: creates the branch at the right parent, re-registers the stack and
  checks the branch out. Move changes into it (or ask your agent to), commit, then **Submit**.
- **Remove from stack**: drop a branch and its commits (branches above are rebased without them), or
  fold it into the branch below/above to keep its commits. Optionally closes its PR and deletes the
  local branch; a stack on GitHub is recreated without it. Conflicts pause with Resolve/Continue/Abort,
  and Abort puts every branch back exactly where it was.
- **Submit**: offers a rebase if needed, asks for titles/bodies/draft state of new PRs, pushes, opens
  them, recreates the GitHub stack when its order changed (GitHub can only append), then
  `gh stack submit --auto`.
- **Sync / Sync & prune / Rebase (stack, upstack, downstack) / Push / Merge** buttons; rebase
  conflicts open the IDE merge tool with Continue/Abort in a banner; sync divergence is detected
  and resolved in a dialog.
- **Layer diff**: a branch's own changes against its parent — exactly what its PR shows.
- **Activity log** under the tree: live output of every operation with Cancel, history and **Undo** (restores
  local branches and the stack to before the last rebase, insert, remove, move or sync).
- **Move Changes to Stack Layer…** (also in the Commit tool window's context menu): commit selected
  uncommitted files on another layer, then rebase the layers above it.
- **Merge status per PR**: draft, review required, changes requested, checks failing/running, conflicts,
  approved, and `⛔ #N` when a lower layer blocks merging. Labels are clickable (PR, checks page, blocking
  branch); hover for details. Statuses refresh every minute.
- **Conflicts** open the IDE merge tool automatically, and the stack rebase continues once they're resolved —
  also when you use the IDE's own "Continue Rebase".
- **Agent tools (MCP)**, off by default: with "Let AI agents use stack tools" on (Settings ▸ Tools ▸ Stacked PRs)
  and the IDE's MCP server enabled (Settings ▸ Tools ▸ MCP Server), agents get
  `stack_view`, `stack_checkout`, `stack_insert_branch`, `stack_remove_branch`, `stack_move_changes`,
  `stack_rebase(_continue/_abort)`, `stack_push`, `stack_submit`, `stack_sync`, `stack_mark_ready` and
  `stack_undo`, which run the same workflows as the UI and show up in the activity log. Turning the setting off
  stops the tools at once; agents see them added or removed after the IDE restarts.
- `gh stack modify` and the interactive `submit` editor run in an IDE terminal tab.

## Shortcuts

Two-stroke chords: prefix `⌘⌥K` (macOS) or `Ctrl+Alt+X` (Windows/Linux), then
**L** switcher · **W** tool window · **U** up · **D** down · **T** top · **B** bottom · **S** sync ·
**R** rebase upstack · **P** push · **I** insert below · **A** add branch.

## Build & run

```bash
./gradlew test                       # unit + real-CLI integration tests (skipped without gh stack)
./gradlew runIde                     # sandbox IDE with the plugin
./gradlew buildPlugin                # build/distributions/*.zip → Settings ▸ Plugins ▸ Install Plugin from Disk
./gradlew verifyPlugin               # binary compatibility with JetBrains' recommended IDE releases
```

Add `-PideLocalPath=/path/to/WebStorm.app` to use an installed IDE instead of downloading one (also
makes `verifyPlugin` check against it), and `-PrunIdeProject=/path/to/repo` to open a project in the
sandbox.

## CI

- `.github/workflows/ci.yml` (push to `main`, PRs, manual): installs `gh stack` v0.1.1 and runs
  `./gradlew test buildPlugin` with `GHSTACK_REQUIRE_CLI=true`, so the CLI integration tests fail
  instead of skipping when the extension is missing; uploads the plugin zip.
- `.github/workflows/verify.yml` (push to `main`, weekly, manual): `./gradlew verifyPlugin`.
- `.github/workflows/release.yml` (tags `v*`): tests, verifies, signs and publishes to JetBrains
  Marketplace, then creates a GitHub release with the signed zip.

## License

[MIT](LICENSE)
