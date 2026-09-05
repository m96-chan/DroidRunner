#!/usr/bin/env python3
"""What counts as a driver having got worse (issue #126).

Every judgement here becomes a red build on somebody's repository, so the
interesting cases are the ones where the answer is "do not fail": a model that
never ran, a phone that was warm, and our own build having changed underneath.
"""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).parent


def matrix(cells, build="a39ddce", model="2511FPC34G", soc="Mediatek MT6899"):
    """A matrix holding exactly the cells a test cares about."""
    rows = []
    for (operator, precision), drivers in cells.items():
        rows.append({"operator": operator, "precision": precision,
                     "id": f"{operator.lower()}-{precision}", "usable": True,
                     "drivers": drivers})
    return {"schema": 1, "drivers": sorted({d for c in cells.values() for d in c}),
            "device": {"manufacturer": "Xiaomi", "model": model, "soc": soc,
                       "sdk": 36, "droidrunner": build},
            "rows": rows}


def took(**extra):
    return {"status": "accelerated", "detail": "TfLiteNnapiDelegate:mtk-mdla_shim", **extra}


def refused(**extra):
    return {"status": "unsupported", "detail": "TfLiteXNNPackDelegate", **extra}


class Comparison(unittest.TestCase):

    def compare(self, before, after):
        with tempfile.TemporaryDirectory() as scratch:
            scratch = pathlib.Path(scratch)
            (scratch / "before.json").write_text(json.dumps(before))
            (scratch / "after.json").write_text(json.dumps(after))
            done = subprocess.run(
                [sys.executable, str(HERE / "compare.py"),
                 str(scratch / "before.json"), str(scratch / "after.json"),
                 "--json", str(scratch / "report.json")],
                capture_output=True, text=True)
            report = {}
            if (scratch / "report.json").exists():
                report = json.loads((scratch / "report.json").read_text())
            return done.returncode, report, done.stdout + done.stderr

    def test_a_driver_that_stopped_taking_an_operator_fails_the_build(self):
        status, report, out = self.compare(
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}}),
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": refused()}}))

        self.assertEqual(1, status)
        self.assertEqual(1, len(report["regressions"]))
        self.assertIn("REGRESSION", out)

    def test_a_driver_that_started_taking_one_is_reported_and_passes(self):
        status, report, _ = self.compare(
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": refused()}}),
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}}))

        self.assertEqual(0, status)
        self.assertEqual(1, len(report["improvements"]))
        self.assertEqual([], report["regressions"])

    def test_nothing_changing_is_the_boring_answer_and_passes(self):
        same = matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}})

        status, report, _ = self.compare(same, same)

        self.assertEqual(0, status)
        self.assertEqual([], report["regressions"])

    def test_a_row_the_cpu_could_not_run_is_not_a_regression(self):
        # #119: excluded means the model is broken, so neither side says
        # anything about a driver.
        status, report, _ = self.compare(
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}}),
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": {"status": "excluded",
                                                            "detail": ""}}}))

        self.assertEqual(0, status)
        self.assertEqual([], report["regressions"])

    def test_a_phone_that_was_not_thermally_stable_does_not_fail_a_build(self):
        # #98: a gate that fires when the phone was warm gets muted in a week.
        status, report, out = self.compare(
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took(stable=True)}}),
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": refused(stable=False)}}))

        self.assertEqual(0, status)
        self.assertEqual([], report["regressions"])
        self.assertEqual(1, len(report["unverified"]))
        self.assertIn("not judged", out)

    def test_our_own_build_changing_is_not_their_driver_changing(self):
        # #124: a cell that moved between two DroidRunner builds may be ours.
        status, report, out = self.compare(
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}}, build="a39ddce"),
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": refused()}}, build="100fa3b"))

        self.assertEqual(0, status)
        self.assertEqual([], report["regressions"])
        self.assertIn("builds differ", out)

    def test_two_different_phones_are_refused_rather_than_compared(self):
        status, _, out = self.compare(
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}}, model="2511FPC34G"),
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": refused()}}, model="NX769J"))

        # Its own status: "could not compare" is not "found a regression".
        self.assertEqual(2, status)
        self.assertIn("different devices", out)

    def test_the_same_phone_before_and_after_it_learned_its_own_soc(self):
        # The first real pair this was pointed at: two runs of one phone, hours
        # apart, from either side of the build that added `soc`. Identifying a
        # device by SoC called them different phones.
        status, report, _ = self.compare(
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}}, soc=None),
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}}))

        self.assertEqual(0, status)
        self.assertEqual([], report["regressions"])

    def test_a_driver_the_phone_no_longer_has_is_reported_not_failed(self):
        status, report, _ = self.compare(
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took(),
                                          "mtk-dsp_shim": took()}}),
            matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}}))

        self.assertEqual(0, status)
        self.assertEqual(1, len(report["driversGone"]))


if __name__ == "__main__":
    unittest.main()
