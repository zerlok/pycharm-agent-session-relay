## Why

This change adds the final MVP relay stage — **Delivery**
([`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) §3) — closing the loop from annotation to
agent. It takes the Exporter's output (`review-delivery` depends on `review-export`) and writes it
to `REVIEW.md` at the project root, notifies the user it is ready, and clears the batch. The user
then returns to their idle session and asks the agent to read it. Writing the file is the whole MVP
deliverable; automatic typed relay into the terminal is a deferred follow-on.

## What Changes

- **Write `REVIEW.md`.** On submit, serialize the pending batch (via `review-export`) and write it
  to `REVIEW.md` at the project base path, on a background thread (never the EDT).
- **Empty batch is a no-op.** If there are no pending comments, no file is written and the user is
  told there is nothing to submit.
- **Notify the user.** After a successful write, show a notification that `REVIEW.md` is ready to
  hand to the idle session. The plugin SHALL NOT open a connection to the agent or type into any
  terminal in this change.
- **Clear the batch.** After a successful submit, clear the pending comments (via the
  `comment-batch` store's `clear`), removing gutter markers and tool-window entries so the next
  review starts clean. On a failed write the batch is left intact.

## Out of scope (later changes)

- **Automatic relay** — typing `read REVIEW.md …` into the active agent terminal widget via
  `TerminalToolWindowManager` (and the `ssh + tmux send-keys` fallback).
- **Multi-session / worktree-aware** delivery and a session registry — the MVP targets a single
  project root and infers no worktrees.
- Remote plan capture; non-Claude delivery targets.

## Capabilities

### New Capabilities
- `review-delivery`: On submit, write the exported batch to `REVIEW.md` at the project root off the
  EDT, notify the user it is ready, clear the batch on success (and preserve it on failure), and
  treat an empty batch as "nothing to submit".

## Impact

- **IntelliJ Platform APIs**: background task / pooled executor (`Task.Backgroundable`),
  `Notification`, local filesystem write at the project base path.
- **Filesystem**: writes `REVIEW.md` at the project root on the local filesystem; for a remote
  agent the session's own sync (e.g. mutagen) carries it to the sandbox — Relay does not manage
  that sync and never uses git as transport.
- **Depends on**: `review-export` (serialization) and `comment-batch` (the batch + clear).
