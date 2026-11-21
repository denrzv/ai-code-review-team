# Polish changelog: addendum.md

Review: `bmad-review` with lenses=structure,prose (structure first, then prose). Reader: humans. Style: Microsoft Writing Style Guide. Baseline: 538 words. No meaning, facts or PRD ID references (FR-x, OQ-x, SM-x) changed.

## Structure

- §C: split the metrics bullet in two. Metrics beyond precision and recall are now one bullet, and the specialist-split hypothesis (SM-1) is another.
- §B: resolution pointers now use one form, "(PRD FR-x)" or "(PRD OQ-x)". Changed in item 1 ("(FR-11)") and item 17 ("Resolved in PRD FR-23 (matching rule)").

## Prose

- Intro: "This holds ... that is kept out of" became "This addendum holds ... kept out of" (clearer subject).
- §A: "behaviour" became "behavior" (US spelling per style guide).
- §B item 4, 5, 13: enum values `SINGLE_AGENT`, `SINGLE` and `SUBMITTED` are now code-formatted, like the other identifiers.
- §B item 5: the two sentences are joined with "yet", and the unclear "it" now reads "the Planner".
- §B item 14: "Only readFile's 200 lines is concrete" became "Only the 200-line limit for `readFile` is concrete" (agreement and code formatting).
- §C bullet 1: "this project's" became "the project's" (removes the repeated "this").
- §C pitfalls: removed the comma before "so they don't stack" (restrictive clause).

## Considered, not applied

- MERGE §B items 4 and 5 (both about SINGLE mode): not applied, so the item numbering stays stable for downstream references.
- Split §B item 13 (SUBMITTED location, undefined types): left as is, because it is one source finding.
- Serial (Oxford) comma: left out, to match the PRD's own usage.
