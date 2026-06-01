# H.3 — `@TimeTravelBody` Annotation + TTD Session on Lucene

## Brief

Annotate the Lucene entry method identified in H.2 with `@TimeTravelBody`. Build
a TTD session that forward-executes to the failure line and back-steps into the
helper that produced the incorrect value.

## Deliverables

- `eval/showcase/lucene/patches/` — patch(es) applying `@TimeTravelBody` to the
  chosen Lucene entry point.
- `eval/showcase/lucene/session.sh` — script that launches the annotated Lucene
  test under the TTD agent + Crochet agent and starts the REPL.
- `eval/showcase/lucene/session-recording.txt` — annotated transcript of the TTD
  session (commands issued, output observed, root cause identified).

## Dependencies

Depends on: H.2 (scenario selection), Phase B (CPS resume).

## Status

Session artefacts live under `eval/showcase/lucene/` once H.3 is complete.
The TTD REPL is the primary interaction surface; H.4 measures its overhead.
