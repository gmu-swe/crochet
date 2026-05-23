# State-Coverage Fuzzing with Crochet: A Case Study

_Phase IV.3 of the Crochet TTD evaluation._ Branch: `unit/IV.3-state-fuzzing`.

> **Question.** Can JVM-level checkpoint/rollback replace setup/teardown in
> coverage-guided fuzzers of stateful targets, and if so, at what point does
> the trade-off become worthwhile?
>
> **Headline.** Yes, on a stateful target with non-trivial init (here:
> ~50 ms `PoolFleet.setup()`), `crochet_scoped` runs the fuzz loop
> **~3.9× faster** than the textbook full-setup-per-iter baseline and
> discovers **~2.1× more branches** in the same 10-minute budget. The
> threshold is set-up cost: at the default ~3 ms init the win flips
> against Crochet (rollback overhead exceeds setup cost). On the
> correctness side, Crochet's lazy klass-swap restore is _partial_ on this
> target — Mode 3 diverges from Mode 1 on 49 / 50 inputs in the
> trace-parity test. We characterise this as "noisy-but-fast" fuzzing
> rather than a behaviour-identical drop-in replacement.

---

_PLACEHOLDER — full writeup landed after the primary benchmark completes._
_See `README.md` for the operational reference and `scripts/aggregate.py`_
_for the canonical summary table._
