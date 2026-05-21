#!/usr/bin/env python3
"""
fix-locality.py — score a trial result by file-level overlap with the canonical Defects4J fix.

Usage (single trial):
    python3 fix-locality.py <trial_result.json>

Usage (batch, writes rescored JSONs to results-rescored/):
    python3 fix-locality.py --batch <results_dir> [--out-dir <out_dir>]

The script adds the following fields to each result JSON:
    agent_modified_files       : all files in agent's diff (from +++ b/<file> lines)
    canonical_modified_files   : files in the D4J src patch (from +++ b/<file> lines)
    agent_modified_prod_files  : agent files that are production code (not test/build)
    file_overlap               : intersection of agent_modified_prod_files and canonical_modified_files
    missed_canonical           : canonical files not in agent's prod set
    extra_prod_files           : agent prod files NOT in canonical set
    fix_locality_score         : 1.0 / 0.5 / 0.0  (see below)
    test_pass_strict           : bool  (primary_pass AND regressions==0 AND NOT compile_fail AND score>=0.5)

fix_locality_score:
    1.0  all canonical files present in agent prod files AND no extra prod files
    0.5  at least one canonical file overlaps, but agent missed some OR has extras
    0.0  zero overlap

"Extra" files criterion: only production-code files count; test files (src/test/),
build files (pom.xml, build.xml, maven-build.xml, *.properties, *.gradle, build/)
are excluded from "extra" counting so they don't penalise agents for legitimate
scaffolding changes.

Canonical patch path: /home/jon/defects4j/framework/projects/<Project>/patches/<N>.src.patch
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path

D4J_PROJECTS_DIR = Path("/home/jon/defects4j/framework/projects")

# Files / path-prefixes to NOT count as "extra" production changes.
# Agents routinely touch these for build compatibility reasons.
BUILD_FILE_PATTERNS = re.compile(
    r"^(pom\.xml|build\.xml|maven-build\.xml|build/|default\.properties"
    r"|.*\.properties|.*\.gradle|.*\.gradle\.kts|lib/|libs/|\.classpath|\.project)$"
)

def is_test_file(path: str) -> bool:
    """Return True if this file is a test-scope file."""
    parts = path.replace("\\", "/")
    return (
        "/src/test/" in parts
        or parts.startswith("src/test/")
        or "/test/java/" in parts
        or "Test.java" in parts
        or "Tests.java" in parts
        or "/test/" in parts
    )

def is_build_file(path: str) -> bool:
    """Return True if this file should be ignored as a build/config artefact."""
    base = os.path.basename(path)
    rel = path.replace("\\", "/")
    return bool(BUILD_FILE_PATTERNS.match(rel) or BUILD_FILE_PATTERNS.match(base))

def is_prod_file(path: str) -> bool:
    """Return True if this path counts as production code for scoring."""
    return not is_test_file(path) and not is_build_file(path)

def extract_modified_files(patch_text: str) -> list[str]:
    """
    Parse a unified diff and return the list of files touched.
    Handles both 'git diff' style (+++ b/<file>) and plain (+++ <file>).
    Returns paths without the leading 'b/' prefix.
    """
    files = []
    for line in patch_text.splitlines():
        if line.startswith("+++ "):
            path = line[4:].strip()
            # Strip leading 'b/' from git-diff format
            if path.startswith("b/"):
                path = path[2:]
            # Ignore /dev/null (deleted files have no content)
            if path == "/dev/null":
                continue
            if path not in files:
                files.append(path)
    return files

def get_canonical_modified_files(project: str, bug_number: int) -> list[str]:
    """
    Read the Defects4J src patch for <project>/<bug_number>.src.patch and return
    the list of modified source files.
    """
    patch_path = D4J_PROJECTS_DIR / project / "patches" / f"{bug_number}.src.patch"
    if not patch_path.exists():
        raise FileNotFoundError(f"Canonical patch not found: {patch_path}")
    patch_text = patch_path.read_text(errors="replace")
    return extract_modified_files(patch_text)

def parse_bug_id(bug_id: str) -> tuple[str, int]:
    """
    Parse 'Lang-10' -> ('Lang', 10), 'Closure-1' -> ('Closure', 1), etc.
    """
    match = re.match(r"^([A-Za-z]+)-(\d+)$", bug_id)
    if not match:
        raise ValueError(f"Cannot parse bug ID: {bug_id!r}")
    return match.group(1), int(match.group(2))

def score_trial(result: dict) -> dict:
    """
    Given a loaded trial result dict, compute all fix-locality fields and return
    a new dict with those fields merged in.
    """
    bug_id = result.get("bug", "")
    project, bug_number = parse_bug_id(bug_id)

    agent_patch = result.get("agent_patch", "") or ""
    agent_modified_files = extract_modified_files(agent_patch)

    canonical_modified_files = get_canonical_modified_files(project, bug_number)

    agent_modified_prod_files = [f for f in agent_modified_files if is_prod_file(f)]
    canonical_set = set(canonical_modified_files)
    agent_prod_set = set(agent_modified_prod_files)

    file_overlap = sorted(canonical_set & agent_prod_set)
    missed_canonical = sorted(canonical_set - agent_prod_set)
    extra_prod_files = sorted(agent_prod_set - canonical_set)

    overlap_count = len(file_overlap)
    if overlap_count == len(canonical_modified_files) and len(extra_prod_files) == 0:
        fix_locality_score = 1.0
    elif overlap_count > 0:
        fix_locality_score = 0.5
    else:
        fix_locality_score = 0.0

    primary_pass = bool(result.get("primary_pass", False))
    regressions = result.get("agent_induced_regressions", [])
    reg_count = len(regressions) if isinstance(regressions, list) else (0 if not regressions else 1)
    compile_fail = bool(result.get("compile_fail", False))

    test_pass_strict = (
        primary_pass
        and reg_count == 0
        and not compile_fail
        and fix_locality_score >= 0.5
    )

    locality_fields = {
        "agent_modified_files": agent_modified_files,
        "canonical_modified_files": canonical_modified_files,
        "agent_modified_prod_files": sorted(agent_modified_prod_files),
        "file_overlap": file_overlap,
        "missed_canonical": missed_canonical,
        "extra_prod_files": extra_prod_files,
        "fix_locality_score": fix_locality_score,
        "test_pass_strict": test_pass_strict,
    }

    return {**result, **locality_fields}

def main():
    parser = argparse.ArgumentParser(description="Score trial results by fix-locality.")
    parser.add_argument("trial_or_flag", nargs="?", help="Path to a trial JSON, or '--batch'")
    parser.add_argument("results_dir", nargs="?", help="Directory with trial JSONs (batch mode)")
    parser.add_argument("--batch", action="store_true", help="Batch mode: process all JSONs in results_dir")
    parser.add_argument("--out-dir", default=None, help="Output directory for rescored JSONs")

    # Support: python fix-locality.py --batch <dir> [--out-dir <out>]
    # or:       python fix-locality.py <single.json>
    # or:       python fix-locality.py <single.json> (prints to stdout)
    args = parser.parse_args()

    # Normalise: resolve batch mode and paths
    batch_mode = args.batch
    results_dir = args.results_dir
    single_file = None

    if batch_mode:
        # --batch was given as a flag; results_dir comes from positional
        if not results_dir and args.trial_or_flag:
            results_dir = args.trial_or_flag
    elif args.trial_or_flag == "--batch":
        # --batch was given as a positional (fallback)
        batch_mode = True
        results_dir = args.results_dir
    elif args.trial_or_flag and not batch_mode:
        single_file = args.trial_or_flag

    if batch_mode:
        if not results_dir:
            parser.error("--batch requires a results directory argument")
        results_path = Path(results_dir)
        out_dir = Path(args.out_dir) if args.out_dir else results_path.parent / "results-rescored"
        out_dir.mkdir(parents=True, exist_ok=True)

        trial_files = sorted(results_path.glob("*.json"))
        # Skip aggregate files
        trial_files = [f for f in trial_files if f.stem not in ("sweep-results",)]

        successes = 0
        errors = 0
        for tf in trial_files:
            try:
                with open(tf) as fh:
                    result = json.load(fh)
                scored = score_trial(result)
                out_path = out_dir / tf.name
                with open(out_path, "w") as fh:
                    json.dump(scored, fh, indent=2)
                print(f"  {tf.name}: fix_locality_score={scored['fix_locality_score']}, "
                      f"test_pass_strict={scored['test_pass_strict']}")
                successes += 1
            except Exception as e:
                print(f"  ERROR {tf.name}: {e}", file=sys.stderr)
                errors += 1

        print(f"\nDone: {successes} scored, {errors} errors. Output in {out_dir}")

    elif single_file:
        with open(single_file) as fh:
            result = json.load(fh)
        scored = score_trial(result)
        # Print just the locality fields
        locality_fields = {k: scored[k] for k in [
            "agent_modified_files", "canonical_modified_files",
            "agent_modified_prod_files", "file_overlap",
            "missed_canonical", "extra_prod_files",
            "fix_locality_score", "test_pass_strict"
        ]}
        print(json.dumps(locality_fields, indent=2))

    else:
        # Read from stdin if no args
        result = json.load(sys.stdin)
        scored = score_trial(result)
        locality_fields = {k: scored[k] for k in [
            "agent_modified_files", "canonical_modified_files",
            "agent_modified_prod_files", "file_overlap",
            "missed_canonical", "extra_prod_files",
            "fix_locality_score", "test_pass_strict"
        ]}
        print(json.dumps(locality_fields, indent=2))


if __name__ == "__main__":
    main()
