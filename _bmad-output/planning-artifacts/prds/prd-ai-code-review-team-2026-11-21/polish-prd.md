# PRD Polish Changelog — 2026-11-21

Review: `bmad-review` lenses=structure,prose (structure first). Reader: humans; style guide: Microsoft Writing Style Guide. No changes to meaning, requirements, numbers, IDs or frontmatter. Word count 2889 → 2880.

## Applied edits (11)

Structure
1. FR-1: merged the redundant bullet "Submission returns an accepted status with a location to poll" into the lead sentence.
2. FR-18: replaced the repeated manifest contents with a pointer to the §3 glossary definition.
3. §9: changed the double-numbered list ("1. **OQ-1:**") to bullets; OQ IDs unchanged.

Prose
4. §0: "is the product requirements" → "defines the product requirements".
5. §1: replaced ambiguous "It produces a report" (antecedent read as Planner) with "The PoC produces a report".
6. FR-2: decision note now states the decision as well as the gap ("…; this PRD defines them").
7. FR-13: split a run-on sentence ("No Planner is involved.").
8. FR-18: put terminal states in code format (`COMPLETED`, `PARTIAL`, `FAILED`, `CANCELLED`) to match the rest of the document (2 places).
9. FR-18, FR-19: "labelled" → "labeled" (US spelling per the style guide).
10. SM-2: "planner replay" → "Planner replay" (glossary term casing).
11. SM-C3: "more agent calls is not more thoroughness" → "do not mean more thoroughness" (agreement).

## Not applied (would change meaning — author decision)

- §10 is titled "Confirmed Decisions", but NFR-5 says the USD 0.50 ceiling is "To be confirmed after the model smoke test".
- FR-9: "trying to change budgets or run operations" is ambiguous (operations on the run, or executing operations?).
- Traceability: SM-2 lists the "fake line" scenario but does not cite FR-14; FR-25 (and FR-1–3, 5, 6, 9, 10, 12, 15, 16, 19) are not validated by any SM.
- §4.4 and §4.6–4.8 have no **Description:** line, while §4.1–4.3 and §4.5 do (left alone to keep the length down).
