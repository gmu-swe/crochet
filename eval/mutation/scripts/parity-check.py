#!/usr/bin/env python3
"""Verify that the killed-mutant set is consistent across modes.

PIT's fork mode is the ground truth (it has historical maturity). We compare:
  - PIT's mutations.xml (fork mode) — by (mutator, lineNumber, methodDesc)
  - baseline-nofork / crochet runner JSON output — by the same key

Outputs a parity table: per-mode (kills, survives, no-cov agreement with PIT).

Note: our custom runner uses PIT's library to enumerate mutants but may
discover a slightly different set than PIT's plugin because PIT applies
coverage-based skipping (NO_COVERAGE) before invoking the mutator engine
for a mutant the test class doesn't reach. We map "outcome=KILLED" in
both modes to PIT's "DETECTED" status, and our SURVIVED to PIT's
SURVIVED + NO_COVERAGE union (since our runner runs the test set
unconditionally, NO_COVERAGE mutants will simply survive).
"""
from __future__ import annotations
import json, pathlib, re, sys, xml.etree.ElementTree as ET


def parse_pit(xml_path: pathlib.Path) -> dict[tuple, str]:
    """Return {(method, methodDesc, lineNumber, mutator, indexes-list): status}."""
    out = {}
    text = xml_path.read_text()
    for m in re.finditer(r"<mutation\s+([^>]*)>(.*?)</mutation>", text, re.S):
        attrs = dict(re.findall(r"(\w+)=['\"]([^'\"]*)['\"]", m.group(1)))
        body = m.group(2)
        def x(tag):
            mm = re.search(rf"<{tag}>(.*?)</{tag}>", body)
            return mm.group(1) if mm else ""
        method = x("mutatedMethod")
        desc = x("methodDescription")
        line = x("lineNumber")
        mutator = x("mutator")
        indexes = ",".join(re.findall(r"<index>(\d+)</index>", body))
        key = (method, desc, int(line) if line else -1, mutator, indexes)
        out[key] = attrs.get("status", "UNKNOWN")
    return out


def parse_runner(json_path: pathlib.Path) -> dict[tuple, str]:
    out = {}
    for line in json_path.read_text().splitlines():
        j = json.loads(line)
        if j.get("summary"):
            continue
        # id looks like: MutationIdentifier [location=Location [clazz=..., method=..., methodDesc=...], indexes=[N,...], mutator=...]
        m = re.search(r"method=([^,\]]+), methodDesc=([^\]]+)\], indexes=\[([^\]]+)\], mutator=(\S+?)\]?$", j["id"])
        if not m:
            continue
        method, desc, idxs, mutator = m.groups()
        key = (method.strip(), desc.strip(), int(j["line"]), mutator.strip(), idxs.replace(" ", ""))
        out[key] = j["outcome"]
    return out


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1] / "results"
    fork_xml = root / "pit-report-fork-r1" / "mutations.xml"
    if not fork_xml.exists():
        fork_xml = next(root.glob("pit-report-fork-*/mutations.xml"), None)
    if not fork_xml:
        print("FATAL: no PIT fork mutations.xml found", file=sys.stderr)
        return 1

    pit = parse_pit(fork_xml)
    print(f"PIT fork: {len(pit)} mutants (file: {fork_xml})")

    for f in sorted(root.glob("baseline-nofork.*.json")) + sorted(root.glob("crochet.*.json")):
        run = parse_runner(f)
        common = pit.keys() & run.keys()
        only_pit = pit.keys() - run.keys()
        only_run = run.keys() - pit.keys()
        # PIT KILLED+TIMED_OUT == runner KILLED;   PIT SURVIVED+NO_COVERAGE == runner SURVIVED
        agree = 0
        disagree = 0
        for k in common:
            pit_killed = pit[k] in ("KILLED", "TIMED_OUT")
            run_killed = run[k] == "KILLED"
            if pit_killed == run_killed:
                agree += 1
            else:
                disagree += 1
        print(f"\n## {f.name}")
        print(f"   {len(run)} mutants in runner; common with PIT: {len(common)}")
        print(f"   only in PIT (skipped by runner)  : {len(only_pit)}")
        print(f"   only in runner (skipped by PIT)  : {len(only_run)}")
        print(f"   parity (killed match)            : {agree} / {len(common)}")
        if disagree:
            print(f"   DISAGREEMENTS: {disagree}")
            shown = 0
            for k in common:
                pit_killed = pit[k] in ("KILLED", "TIMED_OUT")
                run_killed = run[k] == "KILLED"
                if pit_killed != run_killed:
                    print(f"     {k} : PIT={pit[k]}  runner={run[k]}")
                    shown += 1
                    if shown >= 10:
                        print("     ...")
                        break
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
