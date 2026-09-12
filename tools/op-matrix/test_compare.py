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
# The one caller of compare.py, and the one that decides whether anybody reads
# what it printed (#241).
WORKFLOW = HERE.parents[1] / ".github" / "workflows" / "op-matrix.yml"


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


class WhatTheStepSummaryShows(unittest.TestCase):
    """A failing run has to say why, on the stream the workflow keeps (#241).

    The gate holds compare.py's status and prints the capture into the step
    summary, because failing the job and printing nothing is a worse gate than
    the one that never failed. So the refusals — which go to stderr, where a
    tool run at a terminal wants them — are only useful here if the workflow
    captures stderr too. These run compare.py the way op-matrix.yml does,
    `-u` and stderr merged in, and read what `tee` would have written.
    """

    def capture(self, before, after):
        """Exit status, and what the code fence in the summary would hold."""
        with tempfile.TemporaryDirectory() as scratch:
            scratch = pathlib.Path(scratch)
            (scratch / "before.json").write_text(before)
            (scratch / "after.json").write_text(after)
            done = subprocess.run(
                [sys.executable, "-u", str(HERE / "compare.py"),
                 str(scratch / "before.json"), str(scratch / "after.json"),
                 "--json", str(scratch / "report.json")],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            return done.returncode, done.stdout

    def test_two_different_phones_say_so_where_the_summary_will_show_it(self):
        status, summary = self.capture(
            json.dumps(matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}},
                              model="2511FPC34G")),
            json.dumps(matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": refused()}},
                              model="NX769J")))

        # Still its own status: "could not compare" is not "found a
        # regression", whatever it prints on the way out.
        self.assertEqual(2, status)
        self.assertNotEqual("", summary.strip())
        self.assertIn("different devices", summary)
        # Both file names, because the phone this happens to is one whose
        # committed matrix is filed under another phone's name, and the reader
        # has to know which file to go and open.
        self.assertIn("before.json", summary)
        self.assertIn("after.json", summary)
        # And both models. These two share an SoC, which is what the report
        # names everywhere else — saying "MT6899 and MT6899" tells a reader
        # nothing about what it refused to compare.
        self.assertIn("2511FPC34G", summary)
        self.assertIn("NX769J", summary)

    def test_a_matrix_that_cannot_be_read_is_not_a_regression_and_says_why(self):
        status, summary = self.capture("{ this is not json",
                                       json.dumps(matrix(
                                           {("CONV_2D", "int8"):
                                            {"mtk-mdla_shim": took()}})))

        # 2 rather than the 1 `sys.exit(str)` used to give: a baseline nobody
        # can parse must not read as a driver that stopped taking an operator.
        self.assertEqual(2, status)
        self.assertIn("cannot read", summary)
        self.assertIn("before.json", summary)

    def test_the_workflow_captures_the_stream_a_refusal_is_written_to(self):
        # Keeping refusals on stderr is only half a fix: it helps nobody if the
        # step that builds the summary keeps stdout alone, which is how the
        # empty code fence happened. Read as lines rather than parsed — what
        # matters is the one command line an operator would go and look at, and
        # PyYAML is not installed on the runner that runs these tests.
        lines = [line.strip() for line in WORKFLOW.read_text().splitlines()]
        starts = [i for i, line in enumerate(lines)
                  if line.startswith("python") and "compare.py" in line]
        self.assertEqual(1, len(starts), "expected one compare.py invocation")
        # The invocation is wrapped, and the redirection is on the last line of
        # it, so the whole command has to be put back together first.
        command = ""
        for line in lines[starts[0]:]:
            command += " " + line.rstrip("\\")
            if not line.endswith("\\"):
                break
        self.assertIn("2>&1", command)
        self.assertIn("tee comparison.txt", command)

    def test_the_regression_it_does_fail_on_still_reaches_the_same_capture(self):
        # Merging stderr must not cost the report itself, which is the line the
        # gate has always existed to put in front of somebody.
        status, summary = self.capture(
            json.dumps(matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": took()}})),
            json.dumps(matrix({("CONV_2D", "int8"): {"mtk-mdla_shim": refused()}})))

        self.assertEqual(1, status)
        self.assertIn("REGRESSION", summary)


if __name__ == "__main__":
    unittest.main()
