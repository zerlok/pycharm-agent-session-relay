## Context

Second relay stage (**ReviewBatch**), layered on the shipped `review-annotation` surface. Builds on
trusted editor APIs only (ARCHITECTURE §8). The store must be the single source of truth that both
the gutter and the tool window render from, so they can never drift.

## Decisions

### D2 — In-memory batch as a project-level service (no persistence yet)
A `Project`-scoped service holds the pending `ReviewComment` list and notifies listeners (gutter,
tool window) on change. *Why:* persistence is deferred; in-memory avoids `PersistentStateComponent`
+ re-anchoring complexity now. *Trade-off:* comments are lost on IDE restart — acceptable for the
"annotate while the agent is idle → submit promptly" loop. The model fields needed for persistence
(`anchorText`, `contextHash`) are stored now so adding it later is additive.

### D3 — Anchor with a `RangeMarker` plus stored text + context hash
Each comment holds a live `RangeMarker` (tracks in-IDE edits for free) **and** `anchorText` + a
`contextHash` of surrounding lines. *Why:* the marker covers in-IDE edits today; the stored
text/hash seeds out-of-IDE (agent) re-anchoring in a later change. Export reads the marker's
*current* line range at submit time, so a review submitted while the agent is idle is accurate.
*Alternative rejected:* store only line numbers (breaks on any in-session edit; no path to
re-anchoring).

### D7 — Comment subject is an open type; the MVP authors only the line/range scope
Model the **subject** as an open (sealed) type: `Line` / `LineRange` (path + lines), `File` (path,
no range), `Files` (several paths), `Project` (no path). The MVP authors and displays only the
line/range scope; the other cases exist in the type but their authoring is deferred. *Why:* keeping
the subject open costs almost nothing now and makes each deferred scope an additive case later,
never a model rewrite — line/range is the irreducible review primitive.

### D-store — The store is the single source of truth for gutter + tool window
Both the gutter markers and the tool-window list subscribe to the store's change events and render
from it; neither holds its own copy. *Why:* one source of truth means delete/clear stay consistent
across surfaces by construction (Single Source of Truth). Store mutations and the listener callbacks
run on the EDT (UI-affecting), matching ARCHITECTURE §5.3.

### D-hover-vs-stored — Stored-comment markers are distinct from the hover "+"
The transient hover "+" (from `review-annotation`) signals *"add here"*; the stored-comment marker
signals *"a comment exists here"*. They use different icons and lifecycles (hover follows the mouse
and is not in the store; stored markers follow the store). *Why:* conflating them would make a
hovered line look commented and vice-versa.

## Risks / Trade-offs

- **Anchor drift from out-of-IDE edits** → a comment could point at moved lines if the agent edits
  after annotation → mitigated by loop discipline (submit before the agent resumes); the
  `RangeMarker` covers in-IDE edits, and `contextHash` seeds later re-anchoring.
- **VFS lag** → files synced to disk may not be reflected until refreshed → the "Refresh & review"
  action forces an async refresh; frame-activation refresh is left manual for now.

## Open Questions

- Whether "Refresh & review" should also hook frame-activation refresh now or stay manual — default
  manual for this change.
- Gutter marker interaction for delete: inline gutter action vs. popup — decide during implementation.
