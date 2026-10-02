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

- **Stacked PRs tool window** (right side): every local stack as a tree, top → bottom, with PR number
  and status (`✓` merged, `◎` queued, `⚠` needs rebase, `○` open, `◌` not submitted). Double-click a
  branch to switch to it; right-click for insert, remove, layer diff, rebase-from-here, open PR.
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

## Releasing

One-time setup:

1. Create the signing key and certificate (keep them out of git):
   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
   openssl req -key private.pem -new -x509 -days 3650 -subj "/CN=sanex3339" -out chain.crt
   ```
2. Add repository secrets: `CERTIFICATE_CHAIN` (contents of `chain.crt`), `PRIVATE_KEY` (contents of
   `private.pem`), `PRIVATE_KEY_PASSWORD`, and `PUBLISH_TOKEN` from
   <https://plugins.jetbrains.com/author/me/tokens>.
3. **First upload is manual:** build a signed zip locally
   (`CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private.pem)" PRIVATE_KEY_PASSWORD=… ./gradlew signPlugin`)
   and upload `build/distributions/*-signed.zip` at <https://plugins.jetbrains.com/author/me> →
   *Add new plugin* (license: MIT, source: this repository). JetBrains reviews it, usually within a few
   working days.

Every release after that: bump `pluginVersion` in `gradle.properties`, update `<change-notes>` in
`plugin.xml`, commit, then `git tag v<version> && git push --tags`. Each version is reviewed again
before users get it. Pass `-PpublishChannel=beta` to publish to a beta channel instead.

`scripts/dump-keystrokes.py` regenerates the bundled-shortcut fixture used by `KeymapConflictTest`.

## License

[MIT](LICENSE)
