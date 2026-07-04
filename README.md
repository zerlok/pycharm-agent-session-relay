# Agent Session Relay

A JetBrains/PyCharm plugin — **Relay** for short — that lets you review the agent's changes
in your IDE and relay batched, line-anchored comments straight into its running session.

Relay is a **two-way channel** between you and an agent CLI (Claude Code, Codex, or any
agent): the terminal carries **agent → you**, and a batched, line-anchored **review surface**
carries **you → agent** — relayed into the specific session that made the changes.

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
 0. agent edits files  →  they land in your local working tree (synced in, if remote)
 1. Refresh & review  →  VFS refresh so the edits on disk show up
 2. open a file, select lines, leave a comment  (repeat across files; whole-file and batch-level too)
 3. see / edit / delete pending comments  (gutter icons + tool window)
 4. open the tool window, preview the batch, add more
 5. (the agent sits idle, waiting for input)
 6. press Submit  →  Relay writes REVIEW.md at the project root and tells agent to work with it
```

The export uses Claude Code's native reference syntax (`@path/file.py#L10-15` + comment
body), so the agent resolves anchors directly — no in-file comment markers that would
collide with the agent's own edits under bidirectional sync.

## What Relay is *not*

- It does **not** emulate a terminal — it reuses PyCharm's.
- It does **not** render diffs — it reuses PyCharm's diff viewer.
- It does **not** use git as a transport between hosts, and it does **not** manage file sync —
  it only reads and writes your *local* working tree (including the exported `REVIEW.md`).
  Carrying files to and from a remote sandbox is the session's own job. (Reading your local
  working-tree diff for change detection is fine; the constraint is only about cross-host
  commit/push/pull.)

## Development

This is a **spec-driven** repository ([OpenSpec](https://github.com/Fission-AI/OpenSpec)):
every feature starts as a change proposal under `openspec/changes/` before any code. Two
sources of truth, read both before proposing or implementing:

- **`openspec/changes/`** — product requirements: *what the system is for the user* (capture
  modes, user flow, per-capability behavior, scope phasing).
- **[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)** — technical design: *how the solution
  works and why* (domain model, anchor drift, multi-session mechanics, threading, SDK reuse).

```bash
openspec list              # active changes and their status
/opsx:explore              # think through a problem (no implementation)
/opsx:propose "<idea>"     # create a change proposal (proposal + specs + tasks)
/opsx:apply                # implement an existing change's tasks
/opsx:archive              # finalize a completed change
```

### Build & run

The plugin is built with the [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html);
the target IDE is **PyCharm Community 2024.2** (`platformType`/`platformVersion` in
`gradle.properties`). Requires a JDK 21.

```bash
./gradlew buildPlugin   # produce build/distributions/agent-session-relay-<version>.zip
./gradlew runIde        # launch a sandbox PyCharm with the plugin pre-installed (fastest dev loop)
./gradlew verifyPlugin   # run the JetBrains plugin verifier
```

The first build downloads the target IDE (~1 GB) into the Gradle cache.

### Try it in PyCharm (manual test)

The current MVP slice: **hover any line in the editor → a green `+` appears in the gutter →
click it → an inline comment popup opens.** The popup's **Add** button just logs the comment
(`Help → Show Log in Files…` / the IDE console) and pops an "Agent Session Relay" notification —
nothing is stored yet.

Two ways to test:

1. **Sandbox (recommended).** `./gradlew runIde` launches a throwaway PyCharm with the plugin
   already loaded. Open any file and hover a line.
2. **Install into your real PyCharm.** Build the zip, then in PyCharm:
   `Settings → Plugins → ⚙ (gear) → Install Plugin from Disk…` → pick
   `build/distributions/agent-session-relay-0.1.0.zip` → restart. To see the log output, open
   `Help → Show Log in Files…` (or the **Internal Console**) and look for `Relay add-comment:` lines.
