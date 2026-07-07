<!-- Tasks marked [x] were already applied in the session that raised this change; the remaining
     unchecked tasks are Marketplace release steps and are done outside the repo. -->

## 1. plugin.xml description (end-user altitude, spec-consistent)

- [x] 1.1 Rewrite the `<description>` to cover only shipped, user-reachable behavior: gutter-confined
      `+`, right-click "Add review comment", inline comment box with draggable range, submitted
      comments as read-only inline cards (hover for Edit/Delete) plus the Relay Review tool window,
      Submit → `REVIEW.md`, and Refresh & review
- [x] 1.2 Remove the stale claims: stored-comment "gutter markers", whole-file/batch-level authoring,
      and the plugin "telling" the agent
- [x] 1.3 State that authoring works in any open file regardless of VCS status
- [x] 1.4 Confirm `plugin.xml` remains well-formed XML

## 2. Listing metadata & links

- [x] 2.1 Add GitHub source and issue-tracker links to the `<description>`
- [x] 2.2 Keep the source images for the screenshot set under `docs/images/`
- [ ] 2.3 Upload the `docs/images/example-*.png` screenshot set to the Marketplace gallery (web UI)
- [ ] 2.4 Fill the Marketplace dedicated Source Code and Bug Tracker link fields

## 3. README consistency (contributor altitude)

- [x] 3.1 Fix the canonical-flow block: inline cards (not gutter icons), drop whole-file/batch-level,
      and "notifies you to hand it to the agent" (not "tells agent")
- [x] 3.2 Fix the manual-test walkthrough: gutter-hover `+` / right-click entry, inline card as the
      resting indicator, Edit/Delete on the card, remove "right-click a gutter marker to delete"

## 4. Verification

- [x] 4.1 Sweep README and `plugin.xml` for residual "gutter marker/icon", "tells agent",
      "whole-file", "batch-level" claims — none remain
- [ ] 4.2 Note the separate `/opsx:sync` cleanup for the stale "render a persistent gutter marker"
      requirement in the `review-batch` main spec (out of scope for this change)
