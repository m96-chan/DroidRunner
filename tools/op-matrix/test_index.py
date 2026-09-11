#!/usr/bin/env python3
"""What the index of committed matrices is allowed to say (issue #217).

That table is the only place a reader learns which phones were asked anything
at all, and the hand-kept version dropped a driver that had been measured. So
these are about the two ways a generator can still lie: by leaving something
out of a row, and by passing a README that no longer matches the files.
"""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import index  # noqa: E402

HERE = pathlib.Path(__file__).parent

README = """# Committed operator matrices

One file per phone.

| file | SoC | drivers |
| --- | --- | --- |
| `stale.json` | Something Else | `nnapi-reference` |

What a cell claims is elsewhere.
"""


def matrix(soc, drivers):
    """A matrix holding only what the index reads out of one."""
    return {"schema": 1, "device": {"manufacturer": "nubia", "model": "NX769J",
                                    "soc": soc, "sdk": 36,
                                    "droidrunner": "0.0.0-dev 333b2de"},
            "drivers": drivers, "rows": []}


class Indexing(unittest.TestCase):
    """A docs/matrices of our own, and the script pointed at it."""

    def scratch(self, matrices, readme=README):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = pathlib.Path(directory.name)
        for name, content in matrices.items():
            (path / name).write_text(json.dumps(content))
        if readme is not None:
            (path / "README.md").write_text(readme)
        return path

    def run_index(self, directory, *args):
        done = subprocess.run(
            [sys.executable, str(HERE / "index.py"),
             "--matrices", str(directory), *args],
            capture_output=True, text=True)
        readme = directory / "README.md"
        written = readme.read_text() if readme.exists() else ""
        return done.returncode, written, done.stdout + done.stderr


class WhatARowSays(Indexing):

    def test_a_row_carries_every_driver_the_matrix_carries(self):
        # The bug this script exists for: `gpu` was measured on the nubia,
        # committed, and then left out of the index by hand (#140, #217).
        directory = self.scratch({"nubia-nx769j.json": matrix(
            "QTI SM8650 qcom", ["nnapi-reference", "qnn-htp", "gpu"])})
        status, written, out = self.run_index(directory)

        self.assertEqual(0, status, out)
        self.assertIn("| `nubia-nx769j.json` | QTI SM8650 qcom | "
                      "`nnapi-reference`, `qnn-htp`, `gpu` |", written)

    def test_the_soc_is_what_the_phone_said_rather_than_a_tidier_name(self):
        # Every row of the old table was abbreviated. A SoC string somebody
        # has prettied up cannot be matched against a device report.
        directory = self.scratch({"a.json": matrix("Mediatek MT6899 mt6899", ["gpu"])})
        _, written, _ = self.run_index(directory)

        self.assertIn("Mediatek MT6899 mt6899", written)

    def test_a_matrix_naming_no_driver_says_so_rather_than_leaving_a_gap(self):
        directory = self.scratch({"a.json": matrix("QTI SM8650 qcom", [])})
        _, written, _ = self.run_index(directory)

        self.assertIn("| `a.json` | QTI SM8650 qcom | — |", written)

    def test_rows_are_ordered_by_file_so_a_diff_is_about_the_phones(self):
        directory = self.scratch({
            "xiaomi-2511fpc34g.json": matrix("Mediatek MT6899 mt6899", ["gpu"]),
            "google-pixel-10a.json": matrix("Google Tensor G4 stallion", ["gpu"]),
            "nubia-nx769j.json": matrix("QTI SM8650 qcom", ["gpu"]),
        })
        _, written, _ = self.run_index(directory)
        names = [line.split("`")[1] for line in written.split("\n")
                 if line.startswith("| `")]

        self.assertEqual(["google-pixel-10a.json", "nubia-nx769j.json",
                          "xiaomi-2511fpc34g.json"], names)

    def test_the_prose_around_the_table_is_left_alone(self):
        directory = self.scratch({"a.json": matrix("QTI SM8650 qcom", ["gpu"])})
        _, written, _ = self.run_index(directory)

        self.assertTrue(written.startswith("# Committed operator matrices\n"))
        self.assertTrue(written.endswith("What a cell claims is elsewhere.\n"))
        self.assertIn("One file per phone.", written)

    def test_writing_twice_changes_nothing_the_second_time(self):
        directory = self.scratch({"a.json": matrix("QTI SM8650 qcom", ["gpu"])})
        _, once, _ = self.run_index(directory)
        _, twice, _ = self.run_index(directory)

        self.assertEqual(once, twice)


class WhatTheCheckDoes(Indexing):

    def test_a_stale_readme_fails_and_names_the_script(self):
        directory = self.scratch({"nubia-nx769j.json": matrix(
            "QTI SM8650 qcom", ["nnapi-reference", "qnn-htp", "gpu"])})
        status, unchanged, complaint = self.run_index(directory, "--check")

        self.assertEqual(1, status)
        self.assertIn("tools/op-matrix/index.py", complaint)
        # A check that rewrites the thing it is checking is not a check.
        self.assertEqual(README, unchanged)

    def test_the_diff_says_which_row_is_wrong(self):
        directory = self.scratch({"nubia-nx769j.json": matrix(
            "QTI SM8650 qcom", ["nnapi-reference", "qnn-htp", "gpu"])})
        _, _, complaint = self.run_index(directory, "--check")

        self.assertIn("-| `stale.json`", complaint)
        self.assertIn("+| `nubia-nx769j.json`", complaint)

    def test_a_generated_readme_passes(self):
        directory = self.scratch({"a.json": matrix("QTI SM8650 qcom", ["gpu"])})
        self.run_index(directory)
        status, _, out = self.run_index(directory, "--check")

        self.assertEqual(0, status, out)

    def test_the_committed_index_matches_the_committed_matrices(self):
        # The check CI runs, run here as well: this repository's own table
        # against this repository's own matrices, with no arguments at all.
        done = subprocess.run([sys.executable, str(HERE / "index.py"), "--check"],
                              capture_output=True, text=True)

        self.assertEqual(0, done.returncode, done.stdout + done.stderr)


class WhenThereIsNothingToIndex(Indexing):

    def test_an_empty_directory_is_refused_rather_than_emptying_the_table(self):
        # An index built from no matrices is a table saying no phone was ever
        # measured, which is a lie one misplaced --matrices could tell.
        directory = self.scratch({})
        status, unchanged, complaint = self.run_index(directory)

        self.assertNotEqual(0, status)
        self.assertIn("no matrices in", complaint)
        self.assertEqual(README, unchanged)

    def test_a_readme_with_no_table_is_refused(self):
        directory = self.scratch({"a.json": matrix("QTI SM8650 qcom", ["gpu"])},
                                 readme="# No table here\n")
        status, unchanged, complaint = self.run_index(directory)

        self.assertNotEqual(0, status)
        self.assertIn("no table starting with", complaint)
        self.assertEqual("# No table here\n", unchanged)

    def test_a_missing_readme_is_refused(self):
        directory = self.scratch({"a.json": matrix("QTI SM8650 qcom", ["gpu"])},
                                 readme=None)
        status, _, complaint = self.run_index(directory)

        self.assertNotEqual(0, status)
        self.assertIn("cannot read", complaint)


class Splicing(unittest.TestCase):

    def test_only_the_table_is_replaced(self):
        spliced = index.splice(README, ["| file | SoC | drivers |",
                                        "| --- | --- | --- |",
                                        "| `x.json` | S | `d` |"])

        self.assertNotIn("stale.json", spliced)
        self.assertIn("| `x.json` | S | `d` |", spliced)
        self.assertIn("One file per phone.", spliced)
        self.assertTrue(spliced.endswith("What a cell claims is elsewhere.\n"))


if __name__ == "__main__":
    unittest.main()
