# Sweep Summary -- I.4 Trial Results

| Bug         |    C1    |    C2    |    C3    | Score |
|-------------|----------|----------|----------|-------|
| Lang-1      |   FAIL   |   FAIL   |   FAIL   | 0/3   |
| Lang-10     |   TOUT   |   TOUT   |   TOUT   | 0/3   |  [ttt]
| Lang-26     |   PASS   |   PASS   |   PASS   | 3/3   |
| Time-4      |   PASS   |   PASS   |   PASS   | 3/3   |
| Time-11     |   PASS   |   PASS   |   PASS   | 3/3   |
| Math-5      |   PASS   |   PASS   |   PASS   | 3/3   |
| Math-27     |   PASS   |   PASS   |   PASS   | 3/3   |
| Math-3      |   PASS   |   PASS   |   PASS   | 3/3   |
| Math-10     |   PASS   |   PASS   |   PASS   | 3/3   |
| Closure-1   |   PASS   |   PASS   |   PASS   | 3/3   |
| Closure-10  |   PASS   |   PASS   |   TOUT   | 2/3   |  [t]
|-------------|----------|----------|----------|-------|
| TOTAL       |   9/11   |   9/11   |   8/11   |       |

**Wall-clock:** 0s (0m 0s)

## Legend
- PASS: test_pass=true (primary test passes, zero agent-induced regressions)
- FAIL: test_pass=false (primary test still failing)
- CFAIL: agent patch broke compilation
- TOUT: trial timed out (>600s)
- ERR: harness or setup error
- MISS: result file not found

## Footnote: compile_fail vs primary_fail
CFAIL = agent patch introduced a compilation error (distinct from test failing to pass).
FAIL without CFAIL = code compiled, but target test still fails.

## Anomalies
Closure-10: C1=PASS C3=TOUT -- Crochet TTD underperforms baseline
