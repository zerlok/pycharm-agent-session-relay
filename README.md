# Agent Session Relay

A JetBrains/PyCharm plugin — **Relay** for short — that lets you review the agent's changes
in your IDE and relay batched, line-anchored comments straight into its running session.

Relay is a **two-way channel** between you and an agent CLI (Claude Code, Codex, or any
agent): the terminal carries **agent → you**, and a batched, line-anchored **review surface**
carries **you → agent** — relayed into the specific session that made the changes.

Relay targets the increasingly common setup where the agent runs **remotely** — in a tmux
session on a sandbox, with files synced to your machine via [mutagen](https://mutagen.io) —
while your IDE runs **locally**. You review the agent's changes in PyCharm, leave multiple
line-anchored comments, and relay them all back to the idle session in one batch.

> **Status: pre-implementation.** The design is settled (see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md));
> the first code change scaffolds the IntelliJ Platform Plugin Template. There is no build yet.

- **Plugin ID:** `io.github.zerlok.agentsessionrelay`
- **Language:** Kotlin (IntelliJ Platform Plugin Template, Gradle)
- **License:** [MIT](LICENSE)

## The problem

When an agent edits your files remotely and they sync back locally, PyCharm shows you the
diff — but there's no way to attach several line-anchored comments and return them to the
agent as one reviewable batch. You end up retyping feedback into the terminal, losing the
file/line anchoring the agent could otherwise resolve directly.

## What Relay does

Relay is a **line-anchored annotation layer over any file in the project**, batched and
exported to an agent CLI. The canonical flow:

```
 0. agent edits files remotely  →  mutagen syncs them to your machine
 1. open the change view in PyCharm
 2. select lines, leave a comment  (repeat across files)
 3. see / edit / delete pending comments  (gutter icons + tool window)
 4. open the tool window, preview the batch, add more
 5. (the agent sits idle in tmux, waiting for input)
 6. press Submit  →  Relay writes REVIEW.md (mutagen syncs it) and types
                     "read REVIEW.md and address the comments" into the terminal
                  →  the agent resumes and works from your review
```

The export uses Claude Code's native reference syntax (`@path/file.py#L10-15` + comment
body), so the agent resolves anchors directly — no in-file comment markers that would
collide with the agent's own edits under bidirectional sync.

## What Relay is *not*

- It does **not** emulate a terminal — it reuses PyCharm's.
- It does **not** render diffs — it reuses PyCharm's diff viewer.
- It does **not** use git as a transport between hosts — file sync carries everything,
  including the exported `REVIEW.md`. (Reading your *local* working-tree diff for change
  detection is fine; the constraint is only about cross-host commit/push/pull.)

## Design

The full design — core domain model (Comment → ReviewBatch → Exporter → Delivery → Session),
capture modes, the Submit flow, anchor-drift handling, multi-agent/worktree support, and
scope phasing — lives in **[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)**. Read it before
proposing or implementing anything.

The first shippable slice is the single-session review surface (architecture doc §8);
everything else (the terminal launcher, persistence, multi-session delivery, remote plan
capture, non-Claude exporters) is a follow-on layered on the same core.

## Development

This is a **spec-driven** repository. Work flows through [OpenSpec](https://github.com/Fission-AI/OpenSpec):
specs live in `openspec/specs/`, in-flight change proposals in `openspec/changes/`. Before
writing feature code, there should be a corresponding change proposal.

```bash
openspec list              # active changes and their status
/opsx:explore              # think through a problem (no implementation)
/opsx:propose "<idea>"     # create a change proposal (proposal + specs + tasks)
/opsx:apply                # implement an existing change's tasks
/opsx:archive              # finalize a completed change
```

Build, run, and test commands will be added here once the first change scaffolds the
Gradle plugin template.
