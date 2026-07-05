## 1. Make the draft range mutable

- [x] 1.1 Change `CommentDraft` to hold a mutable `start`/`end` line range (currently captured once at open) sourced from `CommentDraftController.rangeFor`
- [x] 1.2 Add a `resize(newStart, newEnd)` path that updates the range highlighter to the new range, clamped to ≥ 1 line and to the document bounds (edges cannot cross)

## 2. Edge hit-testing and cursor affordance

- [x] 2.1 Compute the pixel Y of the range's top and bottom edges from the current line range; define a small grab-zone band around each
- [x] 2.2 On mouse-move within an edge grab-zone (while a draft is open), show the N-S resize cursor; brighten/thicken the edge line to signal draggability (D4)
- [x] 2.3 Suppress the hover "+" affordance while a draft with an active/hoverable edge is present so it does not compete

## 3. Edge drag: claim the gesture and resize live

- [x] 3.1 On `mousePressed` inside an edge grab-zone, enter edge-drag mode (record which edge) and `consume()` the event so the editor does not start a text selection (D2)
- [x] 3.2 On `mouseDragged` in edge-drag mode, map pointer Y → line and call `resize(...)`; update the highlighter live
- [x] 3.3 On `mouseReleased`, exit edge-drag mode; handle release outside the editor and a zero-movement press (no-op resize)

## 4. Hide the box during the drag, rebuild on release

- [x] 4.1 On edge-press, capture the current body text and dispose the inlay so it reserves no space (D1)
- [x] 4.2 On release, re-add the inlay under the range's new bottom line, restore the captured body text, and re-request focus via the existing deferred `IdeFocusManager` path
- [x] 4.3 Ensure the highlighter continues to render (and updates live) while the box is hidden

## 5. Confirm still logs the adjusted range

- [x] 5.1 Confirm that submit reports the current (adjusted) `start`/`end` — the baseline notification + log — with no change to the report-only stub (D6)

## 6. Verification

- [ ] 6.1 Manually verify in `runIde`: open on a line → drag bottom edge down (box hides, code visible) → release (box reappears under new bottom, typed text intact) → submit logs the adjusted range
- [ ] 6.2 Verify top-edge grow/shrink, the one-line clamp, and that an edge drag never leaves a stray text selection
- [ ] 6.3 Verify opening from an existing multi-line selection seeds that range, then edges refine it
