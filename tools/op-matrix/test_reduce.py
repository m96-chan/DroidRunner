#!/usr/bin/env python3
"""What each result from a device becomes in the matrix (issue #96).

The reduction is the part that can quietly lie: every mistake here reads as a
statement about somebody's silicon. No dependencies, so it runs in CI beside
the Kotlin tests rather than only where TensorFlow is installed.
"""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import reduce  # noqa: E402

HERE = pathlib.Path(__file__).parent


def took_everything(id):
    return {"schema": 1, "ok": True, "id": id, "executed": "accelerator",
            "executedBy": "TfLiteNnapiDelegate:mtk-mdla_shim", "iterations": 0}


def took_nothing(id):
    return {"schema": 1, "ok": True, "id": id, "executed": "cpu-fallback",
            "executedBy": "TfLiteXNNPackDelegate", "iterations": 0}


def ran_on_the_cpu(id):
    return {"schema": 1, "ok": True, "id": id, "executed": "cpu",
            "executedBy": "cpu", "iterations": 0}


class WhatOneResultMeans(unittest.TestCase):

    def test_a_delegate_that_took_every_node_is_accelerated(self):
        status, detail = reduce.classify(took_everything("add-int8"))
        self.assertEqual(reduce.ACCELERATED, status)
        self.assertIn("mtk-mdla_shim", detail)

    def test_a_delegate_that_took_nothing_is_unsupported(self):
        self.assertEqual(reduce.UNSUPPORTED, reduce.classify(took_nothing("add"))[0])

    def test_a_refusal_is_unsupported_rather_than_an_error(self):
        # `refused` is the answer a sweep is largely made of. Collapsing it
        # into `error` would lose the only cell that carries information.
        status, _ = reduce.classify(
            {"schema": 1, "ok": False, "code": "refused", "error": "took no operators"})
        self.assertEqual(reduce.UNSUPPORTED, status)

    def test_a_driver_this_phone_does_not_have_is_not_a_refusal(self):
        # "no such driver" and "the driver said no" are different facts, and a
        # table that renders them alike is not worth compiling against.
        for code in ("unknown-device", "not-installed"):
            with self.subTest(code=code):
                status, _ = reduce.classify({"schema": 1, "ok": False, "code": code})
                self.assertEqual(reduce.ABSENT, status)

    def test_anything_else_that_failed_is_an_error(self):
        status, detail = reduce.classify(
            {"schema": 1, "ok": False, "code": "failed", "error": "IllegalArgumentException"})
        self.assertEqual(reduce.ERROR, status)
        self.assertIn("IllegalArgument", detail)

    def test_a_missing_row_is_an_error_and_not_a_silent_pass(self):
        # A sweep that ran out of budget returns fewer rows than it was sent.
        self.assertEqual(reduce.ERROR, reduce.classify(None)[0])

    def test_a_single_operator_that_split_is_reported_as_it_is(self):
        # One operator should not be able to partition. When it does, the
        # model holds more than we think and the cell should say so rather
        # than round to yes or no.
        status, _ = reduce.classify(
            {"schema": 1, "ok": True, "executed": "partial",
             "delegation": {"describe": "1 of 2 operators on the delegate"}})
        self.assertEqual(reduce.PARTIAL, status)


class TheMatrixItBuilds(unittest.TestCase):

    def reduce_to(self, control, sweep, expect_failure=False):
        with tempfile.TemporaryDirectory() as scratch:
            scratch = pathlib.Path(scratch)
            models = {"schema": 1, "generatedBy": "test", "skipped": [], "models": [
                {"id": "add-int8", "operator": "ADD", "precision": "int8",
                 "file": "add-int8.tflite"},
                {"id": "pad-int8", "operator": "PAD", "precision": "int8",
                 "file": "pad-int8.tflite"}]}
            (scratch / "models.json").write_text(json.dumps(models))
            (scratch / "control.json").write_text(
                json.dumps({"schema": 1, "ok": True, "results": control}))
            (scratch / "driver.json").write_text(
                json.dumps({"schema": 1, "ok": True, "results": sweep}))
            done = subprocess.run(
                [sys.executable, str(HERE / "reduce.py"),
                 "--models", str(scratch / "models.json"),
                 "--control", str(scratch / "control.json"),
                 "--sweep", f"mtk-mdla_shim={scratch / 'driver.json'}",
                 "--out", str(scratch / "out")],
                check=not expect_failure, capture_output=True, text=True)
            if expect_failure:
                return done.returncode, done.stderr
            return (json.loads((scratch / "out" / "matrix.json").read_text()),
                    (scratch / "out" / "matrix.md").read_text())

    def test_a_row_the_cpu_could_not_run_is_excluded_not_reported(self):
        # Our own broken model must never arrive in somebody's table as an
        # operator their driver refuses.
        matrix, markdown = self.reduce_to(
            control=[ran_on_the_cpu("add-int8"),
                     {"schema": 1, "ok": False, "code": "failed", "id": "pad-int8",
                      "error": "IllegalArgumentException"}],
            sweep=[took_everything("add-int8"), took_nothing("pad-int8")])

        rows = {row["id"]: row for row in matrix["rows"]}
        self.assertTrue(rows["add-int8"]["usable"])
        self.assertFalse(rows["pad-int8"]["usable"])
        self.assertEqual(reduce.EXCLUDED,
                         rows["pad-int8"]["drivers"]["mtk-mdla_shim"]["status"])
        self.assertIn("did not run on the CPU", markdown)

    def test_the_control_running_on_the_cpu_does_not_exclude_the_row(self):
        # The control has no delegate, so "nothing was accelerated" is its
        # normal answer and must not be read as a broken model.
        matrix, _ = self.reduce_to(
            control=[ran_on_the_cpu("add-int8"), ran_on_the_cpu("pad-int8")],
            sweep=[took_everything("add-int8"), took_nothing("pad-int8")])

        self.assertTrue(all(row["usable"] for row in matrix["rows"]))

    def test_the_table_names_the_operator_the_precision_and_the_driver(self):
        matrix, markdown = self.reduce_to(
            control=[ran_on_the_cpu("add-int8"), ran_on_the_cpu("pad-int8")],
            sweep=[took_everything("add-int8"), took_nothing("pad-int8")])

        self.assertEqual(["mtk-mdla_shim"], matrix["drivers"])
        self.assertIn("| ADD | int8 | ✓ |", markdown)
        self.assertIn("| PAD | int8 | ✗ |", markdown)


    def test_a_table_in_which_nothing_ran_is_refused_rather_than_published(self):
        # The first run of this on hardware excluded all 62 rows — the models
        # never reached the agent — and came back green. An empty table that
        # looks like an answer is worse than no table.
        broken = [{"schema": 1, "ok": False, "code": "invalid-request", "id": id,
                   "error": "model must be a file under /home/runner"}
                  for id in ("add-int8", "pad-int8")]
        status, complaint = self.reduce_to(
            control=broken,
            sweep=[took_nothing("add-int8"), took_nothing("pad-int8")],
            expect_failure=True)

        self.assertNotEqual(0, status)
        self.assertIn("says nothing about any driver", complaint)


if __name__ == "__main__":
    unittest.main()
