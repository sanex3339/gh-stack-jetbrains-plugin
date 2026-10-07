---
name: stacked-prs-ide
description: Stacked pull requests (gh stack) in a repository open in a JetBrains IDE with the Stacked PRs for GitHub plugin. Use whenever the IDE's MCP server offers tools named stack_view, stack_push, stack_submit and so on (in Claude Code e.g. mcp__webstorm__stack_view), to view, check out, insert or remove a layer, move changes between layers, rebase, push, submit, sync, mark ready or undo. Prefer these tools over running the gh stack CLI.
metadata:
  installed-by: stacked-prs-jetbrains-plugin
  plugin-version: "{{version}}"
---

# Stacked PRs through the IDE

The Stacked PRs for GitHub plugin runs `gh stack` workflows for you through the IDE's MCP server. The tools do what
the plugin's buttons do, and the user sees each run in the plugin's activity log.

## When to use these tools

- Tools named `stack_*` come from the JetBrains IDE's MCP server. In Claude Code their names carry the server's prefix,
  e.g. `mcp__webstorm__stack_view`.
- Prefer them over `gh stack …` in a shell. They push in batches when the repository limits how many branches one push
  may update ("Pushes can not update more than N branches"). They recreate the stack on GitHub when its order changed,
  since GitHub can only append. They never wait on an interactive prompt. The user can undo them from the IDE.
- If they're missing, the IDE isn't running, the project isn't open in it, or "Let AI agents use stack tools" is off
  (Settings | Tools | Stacked PRs; the IDE needs a restart after turning it on). Then use the `gh stack` CLI instead.

## Rules

- Pass `projectPath`, the repository root, on every call.
- Call `stack_view` first, and again after anything that changes the stack.
- Run one stack operation at a time.
- While you use these tools, don't also run `gh stack`, or rebase or reset the stack's branches, in a shell.
- Every result ends with the stack's state. When a tool refuses or fails, its message says what to do next, e.g. "call
  stack_rebase, then insert again". Follow it.

## Tools

| Tool | What it does |
|---|---|
| `stack_view` | The current stack top to bottom: each layer's branch, pull request, merge status (draft, needs review, approved, checks failing or running, conflicts) and which lower layer blocks merging it. Also a rebase or conflict in progress, and other local stacks. |
| `stack_checkout(target)` | Checks out a stack's branch. `target`: a branch name, PR number, PR URL or stack number. |
| `stack_insert_branch(name, direction, target)` | Inserts a new empty branch `"below"` (default) or `"above"` a layer (default: the current branch) and checks it out. |
| `stack_remove_branch(branch, mode, close_pr, delete_branch)` | Removes a layer. `mode`: `"drop"` (its commits leave the layers above), `"fold_down"` or `"fold_up"` (its commits stay in the layer below or above). |
| `stack_move_changes(paths, target, message)` | Commits the uncommitted changes of `paths` on layer `target`, rebases the layers above it, and comes back. |
| `stack_rebase(scope)` | Cascading rebase. `scope`: `"stack"` (default), `"upstack"` (current branch to top) or `"downstack"` (trunk to current branch). |
| `stack_rebase_continue` | Continues a rebase or removal that stopped on a conflict, once the files are resolved and added with `git add`. |
| `stack_rebase_abort` | Abandons a stopped rebase or removal and puts every branch back where it was. |
| `stack_push` | Pushes every unmerged layer with `--force-with-lease`. Creates no pull requests. |
| `stack_submit(draft, mark_ready)` | Publishes: rebases if needed, pushes, opens pull requests for layers without one and links the stack on GitHub. |
| `stack_sync(prune)` | Fetches, rebases onto the updated trunk, pushes and refreshes pull request state. `prune` deletes local branches of merged pull requests. |
| `stack_mark_ready(pr_numbers)` | Marks draft pull requests ready for review (drafts block merging the stack). An empty list means every draft in the stack. |
| `stack_undo` | Undoes the last local stack operation (rebase, insert, remove, move changes, sync). Anything already pushed stays on GitHub. |

## Common tasks

**Put part of the work in a new layer below the current branch**
1. `stack_view`.
2. `stack_insert_branch(name, "below")`. The new, empty branch is checked out, and uncommitted changes come along.
3. Commit the files that belong to this layer with `git add` and `git commit`. Then `stack_rebase("upstack")` so the
   layers above include them. From another layer, `stack_move_changes(paths, target, message)` does all of that in one
   call.
4. `stack_submit` opens its pull request. The stack on GitHub is recreated because its order changed.

**Publish or update the pull requests:** `stack_push` updates existing pull requests. `stack_submit` also opens pull
requests for new layers (`draft: true` for drafts).

**Bring the stack up to date with the trunk:** `stack_sync`. Add `prune: true` once lower layers have merged.

**A conflict stops a rebase, removal, move or sync:** the result names the branch. Resolve its conflicted files and
`git add` them, then call `stack_rebase_continue`. To give up instead, call `stack_rebase_abort`. The IDE may have
opened its merge tool for the user, so tell them what you're doing.

**Something went wrong locally:** `stack_undo` restores the branches and the stack to before the last operation.

**Ready to merge:** `stack_view` shows which layer blocks merging. `stack_mark_ready` takes care of drafts.
