"""Run the app release workflow's guards without publishing or signing anything."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/app-release.yml"


def steps():
    # These tests execute the committed run blocks, so a fix to an unused
    # helper cannot make a workflow that still checks out main pass (#252).
    return re.split(r"^      - ", WORKFLOW.read_text(), flags=re.MULTILINE)[1:]


def step_named(name):
    return next(step for step in steps() if step.startswith(f"name: {name}\n"))


def run_block(step):
    return textwrap.dedent(step.split("        run: |\n", 1)[1]).strip() + "\n"


class ReleaseWorkflow(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="droidrunner-release-test-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.env = os.environ.copy()
        # Prevent a caller's git repository selection from reaching fixtures.
        for name in tuple(self.env):
            if name.startswith("GIT_"):
                self.env.pop(name)
        self.env.update(TAG="v1.0.0", GITHUB_OUTPUT=str(self.repo / "outputs"))
        self.git("init", "-q", "--initial-branch=main")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release-test@example.invalid")
        self.git("-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "release")
        self.release_commit = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.check_output(
            ["git", *args], cwd=self.repo, env=self.env, text=True,
        ).strip()

    def run_step(self, name):
        return subprocess.run(
            ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c",
             run_block(step_named(name))],
            cwd=self.repo, env=self.env, text=True, capture_output=True,
        )

    def verify(self):
        return self.run_step("Verify the checkout matches the release tag")

    def test_one_validated_tag_drives_checkout_version_source_and_publication(self):
        validation = step_named("Check the tag is the shape a release can carry")
        self.assertIn("id: release_tag", validation)
        self.assertIn(
            "github.event_name == 'workflow_dispatch' && inputs.tag || github.ref_name",
            validation,
        )
        result = self.run_step("Check the tag is the shape a release can carry")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("tag=v1.0.0\n", (self.repo / "outputs").read_text())
        checkout = next(step for step in steps() if step.startswith("uses: actions/checkout@"))
        self.assertRegex(checkout, r"(?m)^          ref: refs/tags/\$\{\{ steps.release_tag.outputs.tag \}\}$")
        for name, variable in (
            ("Verify the checkout matches the release tag", "TAG"),
            ("Build release APK", "DROIDRUNNER_RELEASE_TAG"),
            ("Build the corresponding source archive", "TAG"),
            ("Publish release", "TAG"),
        ):
            self.assertIn(f"{variable}: ${{{{ steps.release_tag.outputs.tag }}}}", step_named(name))
        ordered = steps()
        guard = ordered.index(step_named("Verify the checkout matches the release tag"))
        self.assertLess(guard, ordered.index(step_named("Build proot jniLibs")))
        self.assertLess(guard, ordered.index(step_named("Decode signing keystore")))

    def test_invalid_tags_never_become_outputs(self):
        for tag in ("", "v1.0.0-rc1", "main", "v1.0.0\nother=value", "v1.0.0; touch injected"):
            with self.subTest(tag=tag):
                self.env["TAG"] = tag
                result = self.run_step("Check the tag is the shape a release can carry")
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.repo / "outputs").exists())

    def test_tag_push_checkout_passes(self):
        self.git("tag", "v1.0.0")
        self.assertEqual(0, self.verify().returncode)

    def test_manual_dispatch_checks_out_requested_tag_even_when_main_is_ahead(self):
        self.git("tag", "v1.0.0")
        self.git("-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "later main")
        self.assertNotEqual(self.release_commit, self.git("rev-parse", "HEAD"))
        # Model the checkout action's explicit ref, read from the workflow.
        checkout = next(step for step in steps() if step.startswith("uses: actions/checkout@"))
        ref = re.search(r"(?m)^          ref: (.+)$", checkout).group(1)
        ref = ref.replace("${{ steps.release_tag.outputs.tag }}", self.env["TAG"])
        self.git("checkout", "-q", "--detach", ref)
        self.assertEqual(self.release_commit, self.git("rev-parse", "HEAD"))
        self.assertEqual(0, self.verify().returncode)

    def test_guard_rejects_a_checkout_from_another_commit(self):
        self.git("tag", "v1.0.0")
        self.git("-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "wrong source")
        self.assertNotEqual(0, self.verify().returncode)

    def test_annotated_tag_is_compared_by_commit(self):
        self.git("-c", "tag.gpgsign=false", "tag", "-a", "v1.0.0", "-m", "release")
        self.assertEqual(0, self.verify().returncode)

    def test_branch_with_tag_name_does_not_satisfy_missing_tag(self):
        self.git("branch", "v1.0.0")
        self.assertNotEqual(0, self.verify().returncode)

    def mock_publisher(self):
        apk = self.repo / "app/build/outputs/apk/release/app-release.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"fixture apk")
        archive = self.repo / "runtime/out/droidrunner-v1.0.0-source.tar.gz"
        archive.parent.mkdir(parents=True)
        archive.write_bytes(b"fixture source")
        bin_dir = self.repo / "bin"
        bin_dir.mkdir()
        gh = bin_dir / "gh"
        gh.write_text(textwrap.dedent("""\
            #!/usr/bin/env python3
            import json, os, sys
            with open(os.environ['GH_TRACE'], 'a') as trace:
                trace.write(json.dumps(sys.argv[1:]) + '\\n')
            if sys.argv[1:3] == ['release', 'create']:
                sys.exit(int(os.environ.get('GH_CREATE_STATUS', '0')))
            sys.exit(0)
            """))
        gh.chmod(0o755)
        self.env["PATH"] = str(bin_dir) + os.pathsep + self.env["PATH"]
        self.env["GH_TRACE"] = str(self.repo / "gh-trace")

    def published_calls(self):
        return [json.loads(line) for line in (self.repo / "gh-trace").read_text().splitlines()]

    def test_publish_requires_existing_remote_tag_and_attaches_both_artifacts(self):
        self.mock_publisher()
        result = self.run_step("Publish release")
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.published_calls()
        self.assertEqual(1, len(calls))
        self.assertEqual(["release", "create", "v1.0.0"], calls[0][:3])
        self.assertIn("--verify-tag", calls[0])
        self.assertNotIn("--clobber", calls[0])
        self.assertIn("droidrunner-v1.0.0.apk", calls[0])
        self.assertIn("runtime/out/droidrunner-v1.0.0-source.tar.gz", calls[0])

    def test_existing_release_or_publication_error_stops_without_overwriting_assets(self):
        self.mock_publisher()
        self.env["GH_CREATE_STATUS"] = "1"
        result = self.run_step("Publish release")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(self.published_calls()))


if __name__ == "__main__":
    unittest.main()
