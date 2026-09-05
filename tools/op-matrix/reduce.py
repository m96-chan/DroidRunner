#!/usr/bin/env python3
"""Turns a sweep into an operator support matrix — issue #96.

One row per (operator, precision), one column per driver, and each cell is
what the device said rather than what a datasheet says. The input is the raw
envelope from `POST /v1/tests/models`, one file per driver, plus the CPU
control sweep — every model run with no device at all.

The control is not a formality. If a model does not run on the CPU either then
the model is broken, and filing that under "the driver does not support this
operator" would put a defect of ours into a table other people compile
against. Such a row is excluded and said to be excluded.

Usage: reduce.py --models models.json --control sweep-cpu.json \
                 --sweep DRIVER=sweep-DRIVER.json [--sweep ...] \
                 [--capabilities capabilities.json] --out DIR
"""
import argparse
import json
import pathlib
import sys

# What a cell can say. `unsupported` and `absent` are different answers and a
# matrix that renders them the same is not worth compiling against: one is a
# driver that exists and said no, the other is a driver this phone does not
# have.
ACCELERATED = "accelerated"
PARTIAL = "partial"
UNSUPPORTED = "unsupported"
ABSENT = "absent"
ERROR = "error"
EXCLUDED = "excluded"

MARK = {ACCELERATED: "✓", PARTIAL: "~", UNSUPPORTED: "✗",
        ABSENT: "·", ERROR: "!", EXCLUDED: "—"}


def results_by_id(path):
    envelope = json.loads(pathlib.Path(path).read_text())
    rows = {row.get("id", str(index)): row
            for index, row in enumerate(envelope.get("results", []))}
    return rows, envelope


# Drivers that are the CPU whatever route reached them. Needed only for the
# fallback below: a result carrying `executed` has already been through the
# agent's own version of this judgement.
CPU_DRIVERS = {"nnapi-reference"}


def executed_of(row, driver):
    """`executed`, or what the delegation says when the field is not there.

    The Qualcomm path reports `delegation` but no `executed` — the contract's
    headline field missing from the one route that reaches an NPU. Treating
    that absence as "the driver took nothing" reported all 62 operators as
    unsupported on a Hexagon that had taken every one of them. So it is derived
    instead, with the same rule the agent applies: a driver that is the CPU is
    the CPU however completely it took the graph.
    """
    # Whatever the device said, this driver is the CPU: the name says so, and
    # it is a fact about NNAPI rather than a measurement. One of the three
    # phones this was first run on carried a build predating that rule and
    # reported 60 of 62 operators as accelerated by `nnapi-reference`.
    if driver in CPU_DRIVERS:
        return "cpu"
    if row.get("executed"):
        return row["executed"]
    delegation = row.get("delegation")
    if not delegation:
        return None
    if delegation.get("partial"):
        return "partial"
    return "accelerator" if delegation.get("delegated", 0) > 0 else "cpu-fallback"


def classify(row, driver=None):
    """One result, as one of the words above."""
    if row is None:
        return ERROR, "the sweep returned no row for this model"
    if row.get("ok"):
        executed = executed_of(row, driver)
        if executed is None:
            return ERROR, "the device did not say what executed this"
        if executed == "accelerator":
            return ACCELERATED, row.get("executedBy") or row.get("requestedDevice", "")
        if executed == "partial":
            # A single-operator model should not be able to split. When one
            # does, the converter emitted more than we think it did, and that
            # is worth seeing rather than rounding to yes or no.
            return PARTIAL, row.get("delegation", {}).get("describe", "")
        return UNSUPPORTED, row.get("executedBy", "")
    code = row.get("code")
    if code == "refused":
        return UNSUPPORTED, row.get("error", "")
    if code in ("unknown-device", "not-installed"):
        return ABSENT, row.get("error", "")
    return ERROR, row.get("error", "") or row.get("message", "")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", required=True)
    parser.add_argument("--control", required=True)
    parser.add_argument("--sweep", action="append", default=[],
                        metavar="DRIVER=FILE")
    parser.add_argument("--capabilities")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    catalogue = json.loads(pathlib.Path(args.models).read_text())
    control, _ = results_by_id(args.control)

    sweeps = {}
    for pair in args.sweep:
        driver, _, path = pair.partition("=")
        if not path:
            sys.exit(f"--sweep wants DRIVER=FILE, got {pair!r}")
        sweeps[driver], _ = results_by_id(path)

    device = {}
    if args.capabilities:
        capabilities = json.loads(pathlib.Path(args.capabilities).read_text())
        hardware = capabilities.get("device", {})
        device = {key: value for key, value in (
            ("manufacturer", hardware.get("manufacturer")),
            ("model", hardware.get("model")),
            ("soc", hardware.get("soc")),
            ("sdk", capabilities.get("android", {}).get("sdk")),
            # A table nobody can trace back to a build is a table nobody should
            # stand behind.
            ("droidrunner", " ".join(filter(None, (
                capabilities.get("appVersion"), capabilities.get("appBuild"))))
             or None),
        ) if value is not None}

    rows, excluded = [], 0
    for model in catalogue["models"]:
        control_status, control_detail = classify(control.get(model["id"]))
        # The control ran with no device, so `unsupported` is the CPU's normal
        # answer — there was no delegate to take anything. Only a model that
        # could not be run at all disqualifies its row.
        usable = control_status != ERROR
        if not usable:
            excluded += 1
        cells = {}
        for driver, sweep in sweeps.items():
            if not usable:
                cells[driver] = {"status": EXCLUDED, "detail": control_detail}
                continue
            row = sweep.get(model["id"])
            status, detail = classify(row, driver)
            cell = {"status": status, "detail": detail}
            # Carried so a comparison can tell "this driver changed its mind"
            # from "the phone was in a state it could not describe" (#98, #126).
            stable = (row or {}).get("conditions", {}).get("stable")
            if stable is not None:
                cell["stable"] = stable
            cells[driver] = cell
        rows.append({"operator": model["operator"], "precision": model["precision"],
                     "id": model["id"], "usable": usable,
                     "controlDetail": control_detail if not usable else None,
                     "drivers": cells})

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    drivers = list(sweeps)
    (out / "matrix.json").write_text(json.dumps(
        {"schema": 1, "device": device, "drivers": drivers,
         "generatedBy": catalogue.get("generatedBy"),
         "skippedModels": catalogue.get("skipped", []),
         "rows": rows}, indent=2) + "\n")

    lines = ["# Operator support matrix", ""]
    if device:
        lines += ["  ".join(f"**{key}** {value}" for key, value in device.items()), ""]
    lines += [f"{MARK[ACCELERATED]} accelerated  {MARK[PARTIAL]} partial  "
              f"{MARK[UNSUPPORTED]} not taken  {MARK[ABSENT]} no such driver  "
              f"{MARK[ERROR]} error  {MARK[EXCLUDED]} excluded (see below)", "",
              "| operator | precision | " + " | ".join(drivers) + " |",
              "| --- | --- | " + " | ".join("---" for _ in drivers) + " |"]
    for row in rows:
        marks = " | ".join(MARK[row["drivers"][d]["status"]] for d in drivers)
        lines.append(f"| {row['operator']} | {row['precision']} | {marks} |")

    if excluded:
        lines += ["", f"{excluded} row(s) excluded: the model did not run on the CPU "
                      "either, so nothing about a driver can be concluded from it."]
    if catalogue.get("skipped"):
        lines += ["", "Operators with no model at this precision, and why:", ""]
        for entry in catalogue["skipped"]:
            lines.append(f"- `{entry['id']}` — {entry['reason']}")
    lines += ["", "Every cell is what the device reported for a model containing that "
                  "one operator. See docs/OPERATOR-MATRIX.md for what a cell does and "
                  "does not claim."]
    (out / "matrix.md").write_text("\n".join(lines) + "\n")

    counted = {}
    for row in rows:
        for cell in row["drivers"].values():
            counted[cell["status"]] = counted.get(cell["status"], 0) + 1
    print(f"{len(rows)} rows x {len(drivers)} drivers: "
          + ", ".join(f"{count} {status}" for status, count in sorted(counted.items())),
          file=sys.stderr)
    # An all-excluded table is not a matrix, and publishing one as though it
    # were is worse than publishing nothing: it looks like an answer. The first
    # run of this on hardware produced exactly that, from models the agent
    # could not read, and came back green.
    if rows and excluded == len(rows):
        sys.exit("every row was excluded: no model ran on the CPU, so this "
                 "sweep says nothing about any driver")


if __name__ == "__main__":
    main()
