#!/usr/bin/env python3
"""End-to-end reports and an optional independent SciPy null-space constraint oracle."""
import argparse
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import random
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CP = str(ROOT / "build/classes") + ":" + str(ROOT / "build/deps/commons-math3-3.6.1.jar")
CHECKS = 0


def check(condition, message):
    global CHECKS
    CHECKS += 1
    if not condition:
        raise AssertionError(message)


def near(actual, expected, message):
    check(math.isclose(actual, expected, abs_tol=1e-8, rel_tol=1e-8), f"{message}: {actual} vs {expected}")


def cli(states, graph, *options, code=0):
    proc = subprocess.run(["java", "-cp", CP, "org.mathhacks.Main", "investigate", str(states), str(graph), *options],
                          cwd=ROOT, text=True, capture_output=True)
    check(proc.returncode == code, f"CLI exit {proc.returncode}, expected {code}: {proc.stderr}")
    return proc


def report(states, graph, *options, code=0):
    text = cli(states, graph, "--assume-equilibrium", "--format", "json", *options, code=code).stdout
    data = json.loads(text, parse_constant=lambda value: (_ for _ in ()).throw(AssertionError("Nonfinite JSON: " + value)))
    check(data["schema_version"] == 1, "schema version")
    return data


def write_model(directory, names, edges):
    states, graph = directory / "states.csv", directory / "graph.csv"
    with states.open("w", newline="") as stream:
        writer = csv.writer(stream); writer.writerow(["name"])
        writer.writerows([[name] for name in names])
    with graph.open("w", newline="") as stream:
        writer = csv.writer(stream); writer.writerow(["state1", "state2", "value", "variance", "ligand", "standard_state"])
        writer.writerows([[names[i], names[j], d, v, "helm", 1] for i, j, d, v in edges])
    return states, graph


def end_to_end(directory, reference_dir):
    states = ROOT / "samples/investigation/states.csv"
    graph = ROOT / "samples/investigation/corrupted.csv"
    data = report(states, graph)
    check(data["status"] == "STATISTICAL_TENSION", "known corrupted input is flagged")
    check(data["inputs"]["graph_sha256"] == hashlib.sha256(graph.read_bytes()).hexdigest(), "input provenance hash")
    witness = data["cycle_witnesses"][0]
    near(abs(witness["closure_kT"]), 1, "signed cycle magnitude")
    near(witness["standard_error_kT"], math.sqrt(0.0012), "measurement uncertainty")
    check(data["sparse_search"]["minimum_changed_count"] == 1, "sparse minimum")
    check(len(data["sparse_search"]["alternatives"]) == 3, "three alternatives")
    fixed = report(states, graph, "--lock", "1,2")
    for edge in fixed["weighted_repair"]["adjustments"][:2]:
        check(edge["locked_by_user"] and not edge["changed"], "user lock annotation")
    near(fixed["weighted_repair"]["adjustments"][2]["repaired_parameter"], -2, "repair under trust policy")
    conflict = report(states, graph, "--lock", "1,2,3", code=3)
    check(conflict["status"] == "INFEASIBLE_LOCK_POLICY" and conflict["weighted_repair"] is None, "infeasible report")
    check(len(conflict["lock_conflict"]["steps"]) == 3, "infeasible lock witness")
    control = report(states, ROOT / "samples/investigation/consistent.csv")
    check(control["status"] == "NO_GLOBAL_TENSION_DETECTED", "consistent control")
    check(control["sparse_search"]["minimum_changed_count"] == 0, "consistent control zero repair")
    proc = cli(states, graph, "--format", "json", code=2)
    check("--assume-equilibrium" in proc.stderr and not proc.stdout, "missing assumptions rejected")
    cli(states, graph, "--assume-equilibrium", "--lock", "0", code=2)
    cli(states, graph, "--assume-equilibrium", "--alpha", "NaN", code=2)
    cli(states, graph, "--assume-equilibrium", "--search-budget", "0", code=2)
    exhausted = report(states, graph, "--search-budget", "1")
    check(exhausted["sparse_search"]["budget_exhausted"] and not exhausted["sparse_search"]["minimum_cardinality_certified"],
          "truncated search is explicit")
    partial = report(states, graph, "--search-budget", "2")
    check(partial["sparse_search"]["minimum_cardinality_certified"] and not partial["sparse_search"]["minimum_level_complete"],
          "minimum size and ranking completeness separated")
    md = cli(states, graph, "--assume-equilibrium").stdout
    check("Signed cycle witnesses" in md and "Candidate parameter" in md and "not identify which measurement" in md, "readable evidence report")
    output = directory / "report.json"
    proc = cli(states, graph, "--assume-equilibrium", "--format", "json", "--output", str(output))
    check(not proc.stdout and output.exists(), "report export keeps stdout clean")
    check(json.loads(output.read_text())["status"] == data["status"], "export agrees with stdout")
    local_states, local_graph = write_model(directory, ['A"q\\', "B|<x>", "C\u03b2"],
                                           [(0, 1, 1, 1), (1, 2, 1, 1), (2, 0, -1, 1)])
    names = report(local_states, local_graph)
    check(names["states"][0]["name"] == 'A"q\\' and names["states"][2]["name"] == "C\u03b2", "JSON escapes round-trip")
    md = cli(local_states, local_graph, "--assume-equilibrium").stdout
    check("B\\|&lt;x&gt;" in md, "Markdown labels escaped")
    original = local_graph.read_bytes()
    cli(local_states, local_graph, "--assume-equilibrium", "--output", str(local_graph), code=2)
    check(local_graph.read_bytes() == original, "direct input overwrite prevented")
    alias = directory / "alias.csv"; alias.symlink_to(local_graph)
    cli(local_states, local_graph, "--assume-equilibrium", "--output", str(alias), code=2)
    check(local_graph.read_bytes() == original, "symlink input overwrite prevented")
    # Distinct input variances produce a known ranking: one-cycle cost is closure^2 / variance.
    local_states, local_graph = write_model(directory, ["A", "B", "C"], [(0, 1, 1, 1), (1, 2, 1, 4), (2, 0, -1, 9)])
    ranked = report(local_states, local_graph)
    near(ranked["weighted_repair"]["measurement_chi_square"], 1 / 14, "weighted closed form")
    for actual, expected in zip(ranked["sparse_search"]["alternatives"], [1 / 9, 1 / 4, 1]):
        near(actual["measurement_chi_square"], expected, "sparse alternative cost ranking")
    published = reference_dir / "yiip-site-a-ph6.5-zn1e-06"
    real = report(published / "states.csv", published / "graph.csv",
                  "--ph", "6.5", "--concentration", "Zn=0.000001", "--max-changes", "0")
    check(len(real["states"]) == 32 and len(real["cycle_witnesses"]) == 49, "published 32-state investigation")
    check(real["conditions"]["concentrations"]["Zn"] == 1e-6, "declared ligand concentration preserved")


def independent_oracle(directory):
    import numpy as np
    from scipy.linalg import null_space
    from scipy.stats import chi2, norm

    rng = random.Random(20261018)
    for case in range(12):
        n = 4 + case % 6
        names = ["S" + str(i) for i in range(n)]
        truth = [rng.gauss(0, 2) for _ in names]
        pairs = [(i, i + 1) for i in range(n - 1)]
        pairs += [(i, j) for i in range(n) for j in range(i + 2, n) if rng.random() < 0.6]
        edges = [(i, j, truth[j] - truth[i] + rng.gauss(0, 0.2), 0.01 + rng.random()) for i, j in pairs]
        states, graph = write_model(directory, names, edges)
        locked = list(range(case % 3))  # independent rows from a spanning chain; always feasible
        options = ["--max-changes", "0"]
        if locked:
            options += ["--lock", ",".join(str(e + 1) for e in locked)]
        result = report(states, graph, *options)
        d = np.array([e[2] for e in edges]); variances = np.array([e[3] for e in edges])
        incidence = np.zeros((len(edges), n))
        for e, (i, j) in enumerate(pairs):
            incidence[e, i] = -1; incidence[e, j] = 1
        # Independent formulation: anchor state 0 and compute a null-space basis
        # of the equality matrix. Java instead contracts fixed-edge components.
        b = incidence[:, 1:] / np.sqrt(variances[:, None]); y = d / np.sqrt(variances)
        if locked:
            c = incidence[locked, 1:]
            x0 = np.linalg.lstsq(c, d[locked], rcond=None)[0]
            z = null_space(c)
        else:
            x0 = np.zeros(n - 1); z = np.eye(n - 1)
        free_design = b @ z
        free_inverse = np.linalg.pinv(free_design)
        x = x0 + z @ (free_inverse @ (y - b @ x0))
        g = np.r_[0, x]
        covariance = np.zeros((n, n)); covariance[1:, 1:] = z @ free_inverse @ free_inverse.T @ z.T
        repair = result["weighted_repair"]
        for actual, expected in zip(repair["state_energies_kT"], g):
            near(actual, expected, "SciPy equality-null-space energies")
        near(repair["measurement_chi_square"], np.sum((incidence @ g - d) ** 2 / variances), "SciPy constrained objective")
        for e, adjustment in enumerate(repair["adjustments"]):
            variance = incidence[e] @ covariance @ incidence[e]
            near(adjustment["conditional_delta_standard_error_kT"], math.sqrt(max(0, variance)), "SciPy conditional uncertainty")
        df = len(edges) - n + 1
        if df:
            near(result["statistics"]["global_p"], chi2.sf(result["statistics"]["chi_square"], df), "SciPy global probability")
        for witness in result["cycle_witnesses"]:
            near(witness["raw_p"], 2 * norm.sf(abs(witness["z_score"])), "SciPy cycle probability")
            near(witness["bonferroni_p"], min(1, witness["raw_p"] * df), "cycle family adjustment")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--oracle", action="store_true")
    parser.add_argument("--reference-dir", type=Path, default=ROOT / "src/test/resources/upstream")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="investigation-test-", dir=ROOT / "build") as temporary:
        directory = Path(temporary)
        if args.oracle:
            independent_oracle(directory)
        else:
            end_to_end(directory, args.reference_dir)
    print(f"PASS: {CHECKS} {'independent SciPy oracle' if args.oracle else 'end-to-end CLI/report'} checks")


if __name__ == "__main__":
    main()
