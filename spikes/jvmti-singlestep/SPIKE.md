# Spike: JVMTI `SINGLE_STEP` overhead

## TL;DR

**Needs workaround.** Enabling `JVMTI_EVENT_SINGLE_STEP` on a thread makes a
JIT-compiled arithmetic kernel **~2300x slower** on OpenJDK 21. The
JIT-compiled method is *not* deopted to the interpreter — it stays at C2 —
but every bytecode fires a callback at ~150 ns/event amortized. SS is
viable only for short bounded windows (O(10)-O(100) bytecodes); not for
whole-method replay of hot code.

## Environment actually tested

- `openjdk version "21.0.10" 2026-01-20` (Ubuntu build 21.0.10+7-Ubuntu-124.04).
- JVMTI headers: `/usr/lib/jvm/java-21-openjdk-amd64/include/`.
- `g++ (Ubuntu 13.3.0-6ubuntu2~24.04.1)`.
- **Not tested: Temurin 24.** Spec said "ideally Temurin 24"; only OpenJDK 21
  present, and per instructions I did not install one. OpenJDK 21 shares the
  same tiered-compilation + JVMTI event model as 24, so the qualitative
  answer should hold. Re-run on 24 before committing port design (see end).

## Setup

- `agent.cpp` — requests `can_generate_single_step_events`, callback does
  `std::atomic<long long>::fetch_add(1, relaxed)`. JNI entry points bound by
  name.
- `Bench.java` — 2000 warmup + 5000 measured iters. Kernel is sum-reduce +
  xorshift over a 4096-element `int[]` (50 bytecodes, compiles to C2).
  `System.nanoTime()` per iter (no JMH).
- Build via `make`; drive via `run.sh`; `make printcomp` regenerates
  `-XX:+PrintCompilation` logs.

## Results

| config  | ns/iter mean | 95% CI +/- | median     | SS events    | vs base   |
|---------|-------------:|-----------:|-----------:|-------------:|----------:|
| base    |       8,919  |        52  |     8,776  |            0 |     1.00x |
| loaded  |       9,093  |        67  |     8,777  |            0 |     1.02x |
| always  |  20,704,748  |    10,474  | 20,682,235 |  675,990,058 |  2,321.4x |
| region  |     350,016  |    74,107  |     8,796  |   10,814,991 |     39.2x |

Derived:

- ~135 K single-step events per kernel iteration (4096-iter inner loop).
- **Per SS event: ~153 ns (always), ~158 ns (region)** — consistent across
  configs. That is the key constant to carry forward.
- `region` median equals base median (8796 vs 8776): most iters unaffected;
  only the windowed iters pay the tax. The `always` 95 % CI is ~0.05 %, so
  the slowdown is not GC noise.
- `loaded` vs `base`: ~2 % from just having the agent attached with the
  capability requested. Noise-adjacent.

## Does enabling SINGLE_STEP deopt the hot method?

**No.** `Bench::kernel` tiers identically in all three configs:

```
# printcomp-always.log (first 11 lines; same for base/loaded)
51   5 %   3  Bench::kernel @ 4 (50 bytes)
51   6     3  Bench::kernel (50 bytes)
51   7 %   4  Bench::kernel @ 4 (50 bytes)            <-- C2 OSR
54   5 %   3  Bench::kernel @ 4 (50 bytes)   made not entrant
55   8     4  Bench::kernel (50 bytes)                <-- C2
57   6     3  Bench::kernel (50 bytes)   made not entrant
```

`made not entrant` here is the normal C1-retired-by-C2 escalation, not
JVMTI-induced. After line 10 (C2 compile), **no further recompile of
`kernel`** across ~105 s of SS-enabled execution. Modern HotSpot implements
SS via a notification hook in compiled code plus interpreter dispatch, not
by interpreter-pinning the method. That matches the flat ~153 ns/event cost
— pure JNI crossing + atomic add, no interpreter takeover.

**Correctness implication:** the cost is a per-event tax, not a mode flip —
predictable and bounded by bytecodes-under-SS.

## Conclusion: **needs workaround**

- *Viable as-is:* rejected. 2300x on SS-covered hot code is catastrophic.
- *Unusable:* rejected. 153 ns/event is fine for stepping through O(10)-
  O(100) bytecodes during a rollback (~1.5-15 us added latency per window).

Recommended path:

1. **Keep SS for narrow bounded windows only.** Audit each
   enable/disable pair in `tagger.cpp` — confirm the disable happens within
   O(10) bytecodes of the enable on the common path. The existing pairs
   (e.g. enable at 976, disable at 1099) already look windowed; verify no
   user code runs between.
2. **Prefer `JVMTI_EVENT_BREAKPOINT`** when "notify me at this exact
   `(method, location)`" is the real intent. `SetBreakpoint` is orders of
   magnitude cheaper in steady state than SS because HotSpot only fires at
   the set location instead of every bytecode.
3. **Use bytecode-rewriting for roll-forward markers.** Machinery already
   exists at
   `/home/jon/crochet/src/main/java/net/jonbell/crij/instrument/RollForwardTransformer.java`.
   Inserting synthetic checkpoints at known bcis during class load avoids
   SS entirely — the right answer for "N fixed program points".
4. **Do not substitute `METHOD_ENTRY/EXIT`** on hot paths — cheaper
   per-event than SS but HotSpot still perturbs compiled code globally.
5. **JDI is not a substitute** for an in-process agent (adds IPC hop).

## Confounds

- Single-machine, single-run. CPU frequency scaling and tenants not
  controlled. 2300x survives those; the 2 % `loaded` overhead is within
  noise.
- GC visible in `max` column (`always` max 28 ms vs mean 20.7 ms, +37 %;
  `loaded` max 125 us = single GC blip). Mean/median clean; CI narrow.
- Relaxed-order atomic counter; 153 ns is the floor — a real agent body
  (e.g. `GetLocalVariable` probes) would push it up.
- `region` config also exercises repeated `SetEventNotificationMode` toggle
  cost; that is folded into its ns/iter.

## To re-test on Temurin 24

- Re-run `make run` and `make printcomp`. Confirm `Bench::kernel` never
  appears with `deoptimized` / `made not entrant` *after* SS is enabled
  (after wall-clock ~60 ms in the log). On 21 it does not.
- Virtual threads out of scope per spec. If they come in: SS behaviour on
  vthreads varies by JDK version; verify.
- If target is a GraalVM build of Temurin 24, re-measure — Graal's JVMTI
  event handling differs from C2 and may change the per-event constant.
- Re-measure with a heavier callback body (matching the real agent's
  inspections) to get a realistic per-event constant; 153 ns is floor.
- Re-measure with `-XX:-TieredCompilation` and `-Xint` for lower bounds.

## Files

- `agent.cpp`, `Bench.java`, `Makefile`, `run.sh` — runnable.
- `printcomp-{base,loaded,always}.log` — PrintCompilation output captured for
  analysis.
- `results.txt` — raw measurement output.
