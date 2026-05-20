# H.2 — Bug-Style Scenario Design

## Brief

Select a TTD-suited scenario from the Lucene baseline established in H.1. Either
(a) a historic Lucene JIRA issue whose symptom-to-cause path is non-obvious from
logs alone, or (b) a synthetic injection into a Lucene test fixture.

## Deliverables

- `eval/showcase/SCENARIO.md` — decision + reproduction steps for the chosen
  scenario. Identifies the entry method to be annotated with `@TimeTravelBody`
  in H.3.

## Dependencies

Depends on: H.1.

## Status

Scenario selection drives H.3–H.5. Design decisions documented in
`eval/showcase/SCENARIO.md` once H.2 is complete.
