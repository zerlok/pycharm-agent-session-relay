## 1. Exporter

- [x] 1.1 Implement the Exporter as a pure function `ReviewBatch -> String` (markdown), with no I/O
- [x] 1.2 Render a line range as `@<project-relative-path>#L<start>-<end>` + body; render a single line as `@<path>#L<n>` (pinned form, D-fmt)
- [x] 1.3 Read the current line range from each comment's `RangeMarker` at export time (D3)
- [x] 1.4 Order comments by file path then start line (D-order)
- [x] 1.5 Handle the empty batch: return a well-defined empty result the delivery stage treats as "nothing to submit"
- [x] 1.6 Ensure project-relative, forward-slash paths; escape bodies so they cannot break the markdown structure

## 2. Verification

- [x] 2.1 Unit-test: single-line form, range form, multiple files ordered, empty batch, and a body with markdown-significant characters
