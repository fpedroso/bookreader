# -*- coding: utf-8 -*-
"""Fail the build if the extractor-agreement test did not actually run.

Gradle filters the test console, so read the JUnit XML instead. The important
case is a SKIPPED test: the test skips itself when the generated fixtures are
missing, which would otherwise be indistinguishable from a pass. A skipped
agreement test is a red build, not a green one.

Usage: check_test_results.py [glob]   (default: app/build/test-results/testDebugUnitTest/*.xml)
"""
import glob
import sys
import xml.etree.ElementTree as ET


def main(pattern: str) -> int:
    files = glob.glob(pattern)
    if not files:
        print(f"no test results matching {pattern!r}", file=sys.stderr)
        return 1

    tests = skipped = failed = 0
    for path in files:
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        skipped += int(root.get("skipped", 0))
        failed += int(root.get("failures", 0)) + int(root.get("errors", 0))

        for case in root.iter("testcase"):
            bad = case.find("failure") is not None or case.find("error") is not None
            was_skipped = case.find("skipped") is not None
            status = "SKIPPED" if was_skipped else ("FAIL" if bad else "pass")
            print(f"  {case.get('name'):<46} {status}")

        # The test prints its coverage numbers to stdout; surface them in CI logs.
        for out in root.iter("system-out"):
            text = (out.text or "").rstrip()
            if text:
                print("  " + text.replace("\n", "\n  "))

    print(f"\ntotals: tests={tests} skipped={skipped} failed={failed}")

    if failed:
        print("ERROR: test failures - see above", file=sys.stderr)
        return 1
    if skipped:
        print(
            "ERROR: a test was SKIPPED, which here means the generated fixtures "
            "were missing. Run tools/gen_hsk1.py and tools/annotate.py first.",
            file=sys.stderr,
        )
        return 1
    if tests == 0:
        print("ERROR: no tests ran at all", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest/*.xml"))
