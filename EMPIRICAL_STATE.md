# EMPIRICAL_STATE.md

**Branch:** `unit/V.2-empirical-state`
**HEAD at start:** `2f55c49` (merge of unit/IV.3-state-fuzzing into java24-tdd)
**Date:** 2026-05-30
**Author:** V.2 audit pass
**Machine:** AMD EPYC 7H12 64-core × 244 logical CPUs, 866 GiB RAM, Linux 6.8.0, JDK 21 Temurin.

This document is the authoritative snapshot of the Crochet TTD empirical evaluation as of
the merge of Phase IV.3 into `java24-tdd`. It (a) summarises what each phase measured, (b)
re-runs the tractable phases from scratch to validate that the committed numbers still
replicate, and (c) tells the bottom-line story across phases.

**Reruns performed in V.2:**

- Phase IV.1 mutation testing — full 3-mode × 3-rep × 272-mutant Fraction sweep. _(in progress / see §5)_
- Phase IV.3 state-coverage fuzzing — primary 4-mode × 3-rep × 5-min campaign at w=50. _(in progress / see §6)_
- Phase I–III aggregator-only re-run from committed trial JSON. _(see §9)_
- One-trial sanity run of the LLM-agent debugging harness on Math-5 × C1 × Opus 4.7. _(see §9)_

_(All re-run raw outputs live under `eval/v2-reproducibility/`.)_

---

## TODO — populated by the audit pass

This is a skeleton. Sections will fill in as reruns finish and findings land.
