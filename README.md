# GH Stack — JetBrains plugin for `gh stack`

A WebStorm (and any JetBrains IDE) UI for GitHub stacked pull requests, built on the
[`gh stack`](https://github.com/github/gh-stack) GitHub CLI extension. Every change goes through the
CLI; the plugin only reads its state files.

## Requirements

- [GitHub CLI](https://cli.github.com), logged in (`gh auth login`)
- The extension: `gh extension install github/gh-stack` (tested with 0.1.x)
- WebStorm or another IntelliJ-based IDE, 2026.2+

## Features

- **GH Stack tool window** (right side): every local stack as a tree, top → bottom, with PR number and
  status (`✓` merged, `◎` queued, `⚠` needs rebase, `○` open, `◌` not submitted). Double-click a
  branch to switch to it; right-click for insert, layer diff, rebase-from-here, open PR.
- **Stack switcher**: the `⧉ api 2/4` dropdown next to the Git branch widget, the status bar widget,
  or `⌘⌥K L` / `Ctrl+Alt+X L`. Lists the stack's branches plus other stacks; click to switch.
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
```

Add `-PideLocalPath=/path/to/WebStorm.app` to use an installed IDE instead of downloading one, and
`-PrunIdeProject=/path/to/repo` to open a project in the sandbox.

`scripts/dump-keystrokes.py` regenerates the bundled-shortcut fixture used by `KeymapConflictTest`.
