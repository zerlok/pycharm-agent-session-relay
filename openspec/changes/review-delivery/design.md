## Context

Final MVP relay stage (**Delivery**), consuming `review-export`'s output and `comment-batch`'s
store. Environment: local-only is the baseline; a remote agent is the complex case, but either way
Relay writes only the **local** working tree and leaves cross-host sync to the session.

## Decisions

### D6 — Delivery (MVP) = write `REVIEW.md`, then notify the user
`REVIEW.md` is written at the project base path (single project root — no worktree inference); for a
remote agent the session's own sync carries it to the sandbox. The plugin then notifies that the
file is ready; the user asks their idle session to read it. *Why:* writing the file is the whole
deliverable — the `@path#L` refs inside resolve natively for Claude, so a human typing
`read REVIEW.md` closes the loop. Deferring typed delivery removes the terminal-widget dependency
(and its targeting ambiguity) from the MVP without blocking the workflow. *Follow-on:* automatic
relay via `TerminalToolWindowManager`, then an `ssh tmux send-keys` fallback.

### D8 — Submit I/O runs off the EDT
The `REVIEW.md` write runs on a background thread (`Task.Backgroundable` / pooled executor). *Why:*
blocking the EDT on file I/O is the classic new-plugin freeze (ARCHITECTURE §5.3). Reading the batch
and serializing (pure, cheap) may happen on the EDT; only the write is backgrounded. The batch clear
and notification happen back on the EDT after the write completes.

### D-empty — Empty batch is a no-op with feedback
Submit with no pending comments writes nothing and informs the user there is nothing to submit,
rather than writing an empty `REVIEW.md`. *Why:* an empty file would read to the agent as "no
feedback," indistinguishable from a mistake; explicit feedback is clearer.

### D-clear — Clear the batch only after a successful write
The batch is cleared (store, gutter, tool window) only once the write succeeds. *Why:* a failed
write must not lose the user's pending comments.

## Risks / Trade-offs

- **File-sync race** (REVIEW.md written locally but not yet on the sandbox when the agent reads it)
  → out of Relay's hands in the MVP: sync is the session's responsibility and the user triggers
  submit, then reads the file deliberately. Relevant only with the automatic-relay follow-on.
- **Overwriting an existing `REVIEW.md`** → the file is a generated artifact regenerated each
  submit; document that it is Relay-owned. (A user-authored `REVIEW.md` collision is unlikely but
  worth a note.)

## Open Questions

- Whether to append a short header/preamble to `REVIEW.md` (e.g. "Review from PyCharm — N comments")
  to orient the agent — decide during implementation; keep it out of the Exporter (delivery concern).
