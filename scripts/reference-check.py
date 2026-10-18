#!/usr/bin/env python3
"""Run pinned author code, record provenance, and compare Java with fresh outputs.

Normal invocation writes only build/. --regenerate recreates the ignored local
fixtures and CLI examples. Requires network for initial setup and Python 3.11+
for the oracle.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "build/reference"
FIXTURES = ROOT / "src/test/resources/upstream"
PINS = {
    "multibind": "7c93f605d99ff67d9adef890c9302bccd2caa1b5",
    "publication": "4c9c28cba3b1a529db1abcaa775448136dad9135",
    "yiip": "be13452486f2355673e8e393c85d5ef6e908823b",
}
URLS = {
    "multibind": "https://github.com/Becksteinlab/multibind.git",
    "publication": "https://github.com/Becksteinlab/multibind-publication-code.git",
    "yiip": "https://github.com/Becksteinlab/yiip_analysis.git",
}


def run(args, **kwargs):
    subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(destination):
    # Imports deliberately happen only inside the independent oracle environment.
    import numpy as np
    import multibind as mb
    from multibind.nonequilibrium import rate_matrix

    destination.mkdir(parents=True, exist_ok=True)
    cases = []

    def energy_cases(name, repo, folder, conditions):
        for suffix, ph, sodium in conditions:
            concentrations = {} if sodium is None else {"Na+": sodium}
            cases.append((name + suffix, "energies", ph, concentrations, repo, folder))

    energy_cases("diamond", "multibind", "examples/input/4-state-diamond",
                 [("-ph" + str(ph), ph, None) for ph in [0, 5, 7, 14]])
    energy_cases("sodium-proton", "multibind", "examples/input/4-state-sodium-proton",
                 [(f"-ph{ph}-na{na}", ph, na) for ph in [4, 7, 10] for na in [0.01, 0.15, 1.0]])
    energy_cases("three-proton-two-bind", "multibind", "examples/input/3-prot-and-2-bind",
                 [(f"-ph{ph}", ph, 0.15) for ph in [4, 7, 10]])
    energy_cases("canonical", "multibind", "examples/input/canonical", [("", 0, None)])
    energy_cases("distortion", "multibind", "tests/input/distortion_system", [("", 0, None)])
    energy_cases("dtpa-fitted", "publication", "nmr_data_fitting/multibind_inputs",
                 [(f"-ph{ph}", ph, None) for ph in [4, 7, 10]])
    for label, folder in [
        ("central", "9dfbfebf-46c2-4b0f-9f61-88a9d3005b9a-center"),
        ("terminal", "0f70019b-8a3d-4ada-a6be-81f80564af7f-side"),
        ("simultaneous", "c13e70b4-ee59-45f8-b113-ab0ed37bbeb3-None"),
    ]:
        energy_cases("dtpa-" + label, "publication", "nmr_data_fitting/runs/" + folder + "/inputs",
                     [(f"-ph{ph}", ph, None) for ph in [3, 7, 11]])
    for ph in [5.6, 6.5, 7.4]:
        for zinc in [1e-12, 1e-6, 0.01]:
            cases.append((f"yiip-site-a-ph{ph}-zn{zinc}", "energies", ph, {"Zn": zinc},
                          "yiip", "MST_inference/siteA"))
    for name, repo, path in [
        ("paper-six-state", "publication", "thermodynamic_rates/inputs/rates.csv"),
        ("paper-transport", "publication", "thermodynamic_rates/inputs/rates_transport.csv"),
        ("paper-md", "publication", "full_antiporter_example/inputs/md_rates.csv"),
        ("author-nine-state", "multibind", "examples/rates/inputs/inputs/rates.csv"),
        ("paper-diffusion", "publication", "full_antiporter_example/inputs/diffusion_rates.csv"),
        ("paper-diffusion-large", "publication", "full_antiporter_example/inputs/diffusion_rates_large.csv"),
        ("paper-diffusion-none", "publication", "full_antiporter_example/inputs/diffusion_rates_none.csv"),
        ("paper-diffusion-scaled", "publication", "full_antiporter_example/inputs/diffusion_rates_scaled.csv"),
    ]:
        cases.append((name, "rates", 0, {}, repo, path))

    metadata = {"paper_doi": "10.1016/j.bpr.2023.100120", "commits": PINS,
                "python": sys.version, "inputs": {}, "cases": len(cases)}
    metadata["environment"] = [
        ("multibind @ git+" + URLS["multibind"] + "@" + PINS["multibind"])
        if line.startswith("multibind @") else line
        for line in subprocess.check_output([sys.executable, "-m", "pip", "freeze"], text=True).splitlines()
    ]
    with (destination / "cases.csv").open("w", newline="") as stream:
        manifest = csv.writer(stream)
        manifest.writerow(["id", "kind", "pH", "Na+", "Zn"])
        for name, kind, ph, concentrations, repo, source in cases:
            case = destination / name
            case.mkdir(exist_ok=True)
            manifest.writerow([name, kind, ph, concentrations.get("Na+", ""), concentrations.get("Zn", "")])
            source = REF / repo / source
            if kind == "energies":
                for filename in ["states.csv", "graph.csv"]:
                    src = source / filename
                    if name.startswith("dtpa-") and filename == "graph.csv":
                        src = source / "best_graph.csv"
                    if repo == "yiip":
                        src = source / ("state.csv" if filename == "states.csv" else "result.csv")
                    shutil.copyfile(src, case / filename)
                    metadata["inputs"][str(src.relative_to(REF))] = digest(src)
                c = mb.Multibind(str(case / "states.csv"), str(case / "graph.csv"))
                c.concentrations = concentrations
                c.build_cycle(pH=ph)
                c.MLE(svd=True)
                rates = errors = None
            else:
                shutil.copyfile(source, case / "rates.csv")
                metadata["inputs"][str(source.relative_to(REF))] = digest(source)
                c, rates, errors = rate_matrix(str(case / "rates.csv"))
            macros = []
            for category in c.states.columns[1:]:
                labels = [str(v) for v in c.states[category].unique()]
                for label in labels[1:]:
                    macros.append((category, labels[0], label))
            with (case / "macros.csv").open("w", newline="") as stream2:
                writer = csv.writer(stream2)
                writer.writerow(["category", "source", "destination"])
                writer.writerows(macros)
            with (case / "expected.csv").open("w", newline="") as stream2:
                writer = csv.writer(stream2)
                writer.writerow(["quantity", "i", "j", "value"])

                def emit(quantity, i, j, value):
                    writer.writerow([quantity, i, j, format(float(value), ".17g")])

                for i in range(len(c.g_mle)):
                    emit("energy", i, 0, c.g_mle[i])
                    emit("probability", i, 0, c.prob_mle.probability.iloc[i])
                    emit("state_error", i, 0, c.std_errors[i])
                    for j in range(len(c.g_mle)):
                        emit("covariance", i, j, c.covariance_matrix[i, j])
                        emit("difference", i, j, c.dGs[i, j])
                        if rates is not None:
                            emit("rate", i, j, rates[i, j])
                            emit("rate_error", i, j, errors[i, j])
                for i, (category, start, end) in enumerate(macros):
                    # Pandas categories in the author API may be numeric.
                    dtype = c.states[category].dtype
                    start_arg = int(start) if np.issubdtype(dtype, np.integer) else start
                    end_arg = int(end) if np.issubdtype(dtype, np.integer) else end
                    value, error = c.effective_energy_difference(category, start_arg, end_arg)
                    emit("macro_energy", i, 0, value)
                    emit("macro_error", i, 0, error)
    # Preserve the incomplete upstream input verbatim as a negative test. Do not infer absent states.
    rejected = destination / "rejected-yiip-site-b"
    rejected.mkdir(exist_ok=True)
    for source_name, target_name in [("states.csv", "states.csv"), ("result.csv", "graph.csv")]:
        src = REF / "yiip/MST_inference/siteB" / source_name
        shutil.copyfile(src, rejected / target_name)
        metadata["inputs"][str(src.relative_to(REF))] = digest(src)
    metadata["rejected_cases"] = {"rejected-yiip-site-b": "State table omits S0Zn through S7Zn referenced by the graph"}
    (destination / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Generated {len(cases)} independent reference cases in {destination}")


def prepare_examples(fixtures):
    """Copy the CLI inputs from generated cases; all example data stays local."""
    examples = ROOT / "examples"
    for name, case in [
        ("diamond", "diamond-ph5"),
        ("dtpa-central", "dtpa-central-ph7"),
        ("dtpa-terminal", "dtpa-terminal-ph7"),
        ("dtpa-simultaneous", "dtpa-simultaneous-ph7"),
        ("yiip-site-a", "yiip-site-a-ph6.5-zn1e-06"),
    ]:
        target = examples / name
        target.mkdir(parents=True, exist_ok=True)
        for filename in ["states.csv", "graph.csv"]:
            shutil.copyfile(fixtures / case / filename, target / filename)
    for filename, case in [
        ("transporter-rates.csv", "paper-six-state"),
        ("transporter-diffusion-rates.csv", "paper-diffusion"),
    ]:
        shutil.copyfile(fixtures / case / "rates.csv", examples / filename)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--regenerate", action="store_true",
                        help="recreate ignored local fixtures and CLI examples from pinned sources")
    parser.add_argument("--worker", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        generate(args.worker)
        return
    if not args.regenerate and not (FIXTURES / "cases.csv").exists():
        raise SystemExit("Missing local reference data. Run 'make data' first.")
    REF.mkdir(parents=True, exist_ok=True)
    for name, pin in PINS.items():
        repo = REF / name
        if not repo.exists():
            run(["git", "clone", "--quiet", URLS[name], repo])
            run(["git", "-C", repo, "checkout", "--quiet", "--detach", pin])
        head = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
        if head != pin:
            raise SystemExit(f"Reference checkout {repo} is not at pinned commit {pin}; use a fresh build/reference directory")
        if subprocess.check_output(["git", "-C", str(repo), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
            raise SystemExit(f"Reference checkout has local changes: {repo}")
    python = REF / "venv/bin/python"
    if not python.exists():
        for candidate in ["python3.13", "python3.12", "python3.11"]:
            executable = shutil.which(candidate)
            if executable:
                run([executable, "-m", "venv", REF / "venv"])
                break
        else:
            raise SystemExit("Install Python 3.11, 3.12 or 3.13 to run the independent oracle")
    requirements = ROOT / "scripts/reference-requirements.txt"
    marker = REF / "environment.sha256"
    lock = digest(requirements) + PINS["multibind"]
    if not marker.exists() or marker.read_text() != lock:
        run([python, "-m", "pip", "install", "-q", "-r", requirements])
        run([python, "-m", "pip", "install", "-q", "--no-deps", REF / "multibind"])
        marker.write_text(lock)
    run([python, "-m", "pytest", "-q"], cwd=REF / "multibind/tests")
    output = FIXTURES if args.regenerate else REF / "fresh"
    run([python, Path(__file__).resolve(), "--worker", output])
    if not args.regenerate:
        # Compare immutable upstream inputs and fresh oracle outputs to the saved fixtures.
        saved = FIXTURES
        if (saved / "cases.csv").read_bytes() != (output / "cases.csv").read_bytes():
            raise SystemExit("Reference case manifest differs from local baseline")
        for expected in saved.glob("*/expected.csv"):
            fresh = output / expected.relative_to(saved)
            with expected.open() as a, fresh.open() as b:
                left, right = list(csv.DictReader(a)), list(csv.DictReader(b))
            if len(left) != len(right):
                raise SystemExit(f"Reference output size changed: {expected}")
            for old, new in zip(left, right):
                import math
                if any(old[key] != new[key] for key in ["quantity", "i", "j"]) or not math.isclose(
                        float(old["value"]), float(new["value"]), rel_tol=1e-10, abs_tol=1e-10):
                    raise SystemExit(f"Reference drift in {expected}: {old} vs {new}")
        old_meta = json.loads((saved / "provenance.json").read_text())
        new_meta = json.loads((output / "provenance.json").read_text())
        if old_meta["inputs"] != new_meta["inputs"]:
            raise SystemExit("Reference input hashes differ from saved provenance")
    run(["make", "test", "REFERENCE_DIR=" + str(output)], cwd=ROOT)
    if args.regenerate:
        prepare_examples(output)
        print("Prepared ignored local fixtures and CLI examples.")


if __name__ == "__main__":
    main()
