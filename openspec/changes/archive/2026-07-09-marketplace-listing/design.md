## Context

The Marketplace listing (the `plugin.xml` `<description>` plus vendor/link/screenshot metadata) is
the only surface most users see before installing. Its behavior claims duplicate what the behavior
capability specs (`review-annotation`, `review-batch`, `review-export`, `review-delivery`) already
define, but nothing binds the two together — so the listing drifted (advertised a removed gutter
icon, unauthored whole-file/batch scopes, and an overstated "tells agent" delivery). This change
makes the listing a governed artifact without duplicating requirement-level detail, honoring the
project's single-source-of-truth principle.

## Goals / Non-Goals

**Goals:**
- Bind listing behavior claims to the behavior specs as the single source of truth.
- Keep the listing at end-user altitude; keep contributor detail in the README.
- Pin the required metadata: vendor, GitHub source link, issue-tracker link, screenshot set.

**Non-Goals:**
- Auto-generating the `<description>` from specs.
- Automated linting/CI that diffs the listing against specs.
- Localization of the listing; Marketplace pricing/category fields.

## Decisions

- **Paraphrase at end-user altitude; specs remain SSOT; consistency is enforced by process, not
  codegen.** The specs are normative `SHALL` text at requirement altitude — wrong register for a
  storefront blurb — so the listing paraphrases. To stop drift, the spec requires any change that
  alters a user-visible behavior to update the listing in the *same* change (a tasks-checklist item),
  rather than restating spec detail in `plugin.xml`.
  - *Alternative rejected:* generate the description from spec text — over-engineering for a
    single-plugin repo and produces prose at the wrong altitude.
- **Screenshots are tracked in-repo at `docs/images/` and uploaded to the Marketplace manually.**
  There is no `plugin.xml` mechanism for the screenshot carousel; the IDE renders it from the
  Marketplace gallery. `docs/images/` is the versioned source of truth; upload is a release step.
  The animated GIF stays README-only (Marketplace screenshot slots do not reliably animate).
- **Links live in two places.** The GitHub and issue links go in the `<description>` (visible in the
  in-IDE plugin panel) and in the Marketplace's dedicated Source Code / Bug Tracker fields.

## Risks / Trade-offs

- **Manual consistency can still drift.** → The spec makes "update the listing" part of any
  behavior-changing change; the apply/archive checklist is the enforcement point.
- **Screenshots live outside version control once uploaded.** → Keep the source PNGs under
  `docs/images/`; treat the Marketplace upload as a reproducible release step from those files.
- **The `review-batch` main spec still literally says "render a persistent gutter marker,"** stale
  since `editor-review-visibility`. → Out of scope here; flagged for a separate `/opsx:sync` cleanup
  so this change stays listing-only.
