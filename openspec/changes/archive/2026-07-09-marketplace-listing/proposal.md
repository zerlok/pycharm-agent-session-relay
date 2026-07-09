## Why

The plugin's Marketplace listing — the `plugin.xml` `<description>` and its metadata — restates
end-user behavior whose canonical source is `openspec/specs/`. Nothing requires it to stay in sync,
so it drifts silently: the `editor-review-visibility` change removed the stored-comment gutter icon,
but the description still advertised "gutter markers", and it also described modeled-but-unauthored
scopes (whole-file / batch-level comments) and overstated delivery ("tells agent"). Drift in the
listing misleads the very users deciding whether to install.

## What Changes

- Introduce a `marketplace-listing` capability that treats the Marketplace listing as a tracked
  artifact with requirements, rather than free-form prose.
- Require the `plugin.xml` `<description>` to describe only **shipped, user-reachable** behavior at
  **end-user altitude** — no modeled-but-unauthored scopes, no deferred features stated as present,
  and no contributor-facing detail (that is the README's role).
- Require behavior claims in the listing to be **consistent with — and derived from — the behavior
  specs** (`review-annotation`, `review-batch`, `review-export`, `review-delivery`), and to be
  updated in the same change that alters the underlying behavior. Reuse the specs as the single
  source of truth rather than duplicating requirement-level detail.
- Require listing **metadata** to be present: vendor, a GitHub source link, an issue-tracker link,
  and a screenshot set.
- First implementation: today's already-applied `plugin.xml` and `README.md` corrections (dropped
  the stale stored-comment gutter-marker claims, the whole-file/batch-level scopes, and the "tells
  agent" overstatement; added the gutter-confined `+`, right-click entry, inline card, and Refresh
  & review), plus the GitHub/issue links and `docs/images/` screenshot set.

## Capabilities

### New Capabilities
- `marketplace-listing`: what the Marketplace listing (the `plugin.xml` `<description>` and its
  metadata) must contain, at what altitude, and the rule that its behavior claims stay consistent
  with the behavior specs instead of restating them.

### Modified Capabilities
<!-- none: the behavior specs (review-annotation/-batch/-export/-delivery) are unchanged; this
     change only governs how the listing reflects them. -->

## Impact

- `src/main/resources/META-INF/plugin.xml` — the `<description>`, vendor, and links.
- The JetBrains Marketplace listing — screenshots (uploaded via the Marketplace UI, sourced from
  `docs/images/`) and the Source Code / Bug Tracker link fields.
- `README.md` — only insofar as it must not be the listing's altitude (it stays contributor-facing);
  its behavior claims share the same consistency rule.
- No runtime code, domain, or behavior-spec changes.
