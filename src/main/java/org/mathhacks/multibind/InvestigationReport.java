package org.mathhacks.multibind;

import java.util.*;

/** Markdown for inspection and strict JSON for export; both come from the same calculated result. */
public final class InvestigationReport {
    private InvestigationReport() { }
    private static final List<String> ASSUMPTIONS = List.of(
        "Operator asserts compatible equilibrium/standard-state molecular-state measurements and complete relevant states.",
        "Energy differences use dimensionless beta*G (kT); pH and ligand concentrations are fixed as declared.",
        "Measurement errors are independent Gaussian with known supplied variances; correlations are not modelled.",
        "Exact locks are a trust policy enforced within floating-point tolerance, not zero-variance measurements.",
        "Repair costs measure agreement with input edge measurements; no independent trajectories or experimental observations were fitted.");
    private static final List<String> LIMITS = List.of(
        "A cycle implicates a set of incompatible means; it does not identify which measurement is wrong.",
        "Nonzero closure is expected with noisy data. Statistical tests depend on the stated noise model.",
        "Repairs are optional algebraic projections, not a recommendation to correct ordinary measurement noise.",
        "Witnesses form a fundamental cycle basis, not all simple cycles or a globally shortest conflicting set.",
        "Sparse support minimality uses algebraic feasibility within tolerance, not the statistical significance threshold.",
        "Candidate uncertainty is conditional on fixed parameters and the selected support; selection uncertainty is not included.",
        "A consistent candidate is not evidence of correct chemistry or a complete model.");

    public static String json(Investigation.Result result) { return encode(data(result)) + "\n"; }

    public static Map<String, Object> data(Investigation.Result result) {
        var options = result.options(); var fit = result.fit(); var analysis = result.analysis();
        List<Object> states = new ArrayList<>(), edges = new ArrayList<>(), cycles = new ArrayList<>();
        for (int i = 0; i < fit.size(); i++) states.add(map("name", fit.model().states().get(i).name(),
            "unconstrained_energy_kT", fit.energy(i), "unconstrained_probability", fit.probability(i),
            "paper_centered_standard_error", fit.paperStateStandardError(i)));
        for (int e = 0; e < fit.model().edges().size(); e++) {
            var edge = fit.model().edges().get(e);
            edges.add(map("edge", e + 1, "from", edge.from(), "to", edge.to(), "ligand", edge.ligand(),
                "original_parameter", edge.value(), "original_parameter_variance", edge.variance(),
                "parameter_unit", parameterUnit(edge), "standard_state", edge.standardState(),
                "input_delta_kT", fit.inputDelta(e), "input_variance_kT2", fit.inputVariance(e),
                "unconstrained_residual_kT", fit.edgeResidual(e), "locked_by_user", options.lockedEdges().contains(e)));
        }
        for (var cycle : analysis.witnesses()) cycles.add(witness(cycle));
        List<Object> alternatives = new ArrayList<>();
        if (result.sparseSearch() != null)
            for (var candidate : result.sparseSearch().candidates()) alternatives.add(candidate(result, candidate));
        Map<String, Object> search = null;
        if (result.sparseSearch() != null) {
            var s = result.sparseSearch();
            search = map("status", s.status(), "supports_tested", s.supportsTested(), "budget", s.budget(),
                "requested_change_limit", options.maxChanges(), "explored_change_limit", s.exploredChangeLimit(),
                "minimum_changed_count", s.minimumChangedCount(), "minimum_cardinality_certified", s.minimumCardinalityCertified(),
                "minimum_level_complete", s.minimumLevelComplete(), "budget_exhausted", s.budgetExhausted(),
                "alternatives", alternatives);
        }
        return map("schema_version", 1, "status", result.status(), "assumptions", ASSUMPTIONS, "limitations", LIMITS,
            "inputs", map("states_path", result.statesPath(), "states_sha256", result.statesHash(),
                          "graph_path", result.graphPath(), "graph_sha256", result.graphHash()),
            "conditions", map("pH", options.pH(), "concentrations", new TreeMap<>(options.concentrations())),
            "numerical_feasibility_tolerance", "1e-10 + 1e-12 * local absolute energy scale",
            "statistics", map("chi_square", analysis.chiSquare(), "degrees_of_freedom", analysis.degreesOfFreedom(),
                              "global_p", analysis.globalP(), "alpha", analysis.alpha(), "global_tension", analysis.globalTension(),
                              "fisher_condition_number", fit.conditionNumber(), "cycle_p_adjustment", "Bonferroni over this basis"),
            "states", states, "edges", edges, "cycle_witnesses", cycles,
            "weighted_repair", result.weightedRepair() == null ? null : candidate(result, result.weightedRepair()),
            "lock_conflict", result.lockConflict() == null ? null : witness(result.lockConflict()),
            "sparse_search", search);
    }
    private static Map<String, Object> witness(CycleAnalysis.Witness witness) {
        List<Object> steps = new ArrayList<>();
        for (var step : witness.steps()) steps.add(map("edge", step.edge() + 1, "direction", step.direction(),
            "from", step.from(), "to", step.to(), "signed_delta_kT", step.signedEnergy()));
        return map("steps", steps, "closure_kT", witness.closure(), "standard_error_kT", witness.standardError(),
            "z_score", witness.zScore(), "raw_p", witness.rawP(), "bonferroni_p", witness.adjustedP(),
            "numerical_discrepancy", witness.numericalDiscrepancy(), "statistically_flagged", witness.statisticallyFlagged());
    }
    private static Map<String, Object> candidate(Investigation.Result result, Repairs.Candidate candidate) {
        List<Object> values = new ArrayList<>(), energies = new ArrayList<>();
        for (int i = 0; i < result.fit().size(); i++) energies.add(candidate.energy(i));
        for (var a : candidate.adjustments()) values.add(map("edge", a.edge() + 1, "before_delta_kT", a.before(),
            "after_delta_kT", a.after(), "adjustment_in_measurement_sigmas", a.sigmaUnits(),
            "original_parameter", a.originalParameter(), "repaired_parameter", a.repairedParameter(),
            "parameter_unit", parameterUnit(result.fit().model().edges().get(a.edge())),
            "conditional_delta_standard_error_kT", a.conditionalStandardError(), "changed", a.changed(),
            "locked_by_user", result.options().lockedEdges().contains(a.edge()),
            "fixed_for_this_support", a.locked()));
        return map("changed_count", candidate.changedCount(), "measurement_chi_square", candidate.chiSquare(),
                   "state_energies_kT", energies, "adjustments", values);
    }

    public static String markdown(Investigation.Result r) {
        var a = r.analysis(); var fit = r.fit();
        StringBuilder out = new StringBuilder("# Model investigation\n\n");
        out.append("**Status: ").append(r.status()).append("**\n\n");
        out.append(fit.size()).append(" states; ").append(fit.model().edges().size()).append(" transitions; ")
           .append(a.degreesOfFreedom()).append(" independent cycle constraints.\n\n");
        out.append("## Conditions and provenance\n\n")
           .append("- States: ").append(md(r.statesPath())).append("; SHA-256 `").append(r.statesHash()).append("`\n")
           .append("- Graph: ").append(md(r.graphPath())).append("; SHA-256 `").append(r.graphHash()).append("`\n")
           .append("- pH: ").append(f(r.options().pH())).append("; ligand concentrations: ")
           .append(md(new TreeMap<>(r.options().concentrations()).toString())).append("\n")
           .append("- User-locked edge numbers: ")
           .append(r.options().lockedEdges().stream().sorted().map(e -> e + 1).toList()).append("\n\n");
        out.append("## Measurement agreement\n\n")
           .append("Unconstrained measurement chi-square: **").append(f(a.chiSquare())).append("**; degrees of freedom: ")
           .append(a.degreesOfFreedom()).append("; global p: ").append(a.globalP() == null ? "not applicable (no cycles)" : f(a.globalP()))
           .append("; significance level: ").append(f(a.alpha())).append(".\n\n")
           .append("Cycle p-values below are Bonferroni-adjusted over this basis. Shared-edge cycles are correlated. ")
           .append("A nonzero numerical closure alone is not a statistically flagged discrepancy.\n\n");
        if (fit.conditionNumber() > 1e8) out.append("**Numerical sensitivity warning:** Fisher condition number ")
            .append(f(fit.conditionNumber())).append(". Interpret small residuals with care.\n\n");
        out.append("## Signed cycle witnesses\n\n")
           .append("Fundamental cycle basis, ordered by absolute z-score; not an exhaustive or shortest-cycle search. ")
           .append("Showing up to 10 of ").append(a.witnesses().size()).append("; JSON retains the complete basis.\n\n");
        if (a.witnesses().isEmpty()) out.append("This graph has no cycles to test.\n\n");
        int count = 0;
        for (var witness : a.witnesses()) {
            if (++count > 10) break;
            appendWitness(out, witness, "Cycle " + count);
        }
        if (r.lockConflict() != null) {
            out.append("## Incompatible exact locks\n\nThese locked measurements cannot all be preserved. No repair was issued.\n\n");
            appendWitness(out, r.lockConflict(), "Locked-cycle certificate");
        } else {
            out.append("## Weighted repair\n\nMinimizes squared adjustments divided by measurement variances, subject to the exact user locks.\n\n");
            appendCandidate(out, r, r.weightedRepair());
            var search = r.sparseSearch();
            out.append("## Sparse alternatives\n\n").append(search.status()).append(". Supports tested: ")
               .append(search.supportsTested()).append(" / ").append(search.budget()).append("; explored change limit: ")
               .append(search.exploredChangeLimit()).append(".\n\n")
               .append("Minimum-cardinality certificate within numerical tolerance: ")
               .append(search.minimumCardinalityCertified()).append("; all supports at that size evaluated: ")
               .append(search.minimumLevelComplete()).append("; budget exhausted: ").append(search.budgetExhausted()).append(".\n\n");
            int alternative = 0;
            for (var candidate : search.candidates()) {
                out.append("### Alternative ").append(++alternative).append("\n\n"); appendCandidate(out, r, candidate);
            }
        }
        out.append("## Assumptions and interpretation\n\n");
        for (String assumption : ASSUMPTIONS) out.append("- ").append(assumption).append('\n');
        for (String limit : LIMITS) out.append("- ").append(limit).append('\n');
        out.append("- Numerical feasibility tolerance: 1e-10 + 1e-12 times the local absolute energy scale.\n\n")
           .append("Foundation: [Kenney & Beckstein (2023)](https://doi.org/10.1016/j.bpr.2023.100120). ")
           .append("The cycle diagnostics and bounded sparse-search workflow are project extensions.\n");
        return out.toString();
    }
    private static void appendWitness(StringBuilder out, CycleAnalysis.Witness w, String title) {
        out.append("### ").append(title).append("\n\n");
        for (var s : w.steps()) out.append("- Edge ").append(s.edge() + 1).append(" (")
            .append(s.direction() > 0 ? "+" : "-").append(" input orientation), ").append(md(s.from())).append(" -> ")
            .append(md(s.to())).append(": ").append(f(s.signedEnergy())).append(" kT\n");
        out.append("\nSigned sum: **").append(f(w.closure())).append(" kT**; propagated standard error: ")
           .append(f(w.standardError())).append(" kT; z: ").append(f(w.zScore()))
           .append("; adjusted p: ").append(f(w.adjustedP())).append(". Numerical discrepancy: ")
           .append(w.numericalDiscrepancy()).append("; statistically flagged: ").append(w.statisticallyFlagged()).append(".\n\n");
    }
    private static void appendCandidate(StringBuilder out, Investigation.Result r, Repairs.Candidate candidate) {
        out.append("Changed parameters: ").append(candidate.changedCount()).append("; measurement chi-square: ")
           .append(f(candidate.chiSquare())).append(". Showing changed edges and user locks; other parameters are preserved.\n\n")
           .append("| Edge | Transition | Input parameter | Candidate parameter | Unit | Adjustment (energy sigma) | User locked |\n")
           .append("|---|---|---:|---:|---|---:|---|\n");
        for (var a : candidate.adjustments()) {
            if (!a.changed() && !r.options().lockedEdges().contains(a.edge())) continue;
            var edge = r.fit().model().edges().get(a.edge());
            out.append("| ").append(a.edge() + 1).append(" | ").append(md(edge.from())).append(" -> ")
               .append(md(edge.to())).append(" | ").append(f(a.originalParameter())).append(" | ")
               .append(f(a.repairedParameter())).append(" | ").append(parameterUnit(edge)).append(" | ")
               .append(f(a.sigmaUnits())).append(" | ").append(r.options().lockedEdges().contains(a.edge())).append(" |\n");
        }
        out.append('\n');
    }
    private static String parameterUnit(Model.Edge edge) { return edge.ligand().equalsIgnoreCase("H+") ? "pKa" : "kT"; }
    private static String f(double value) { return String.format(Locale.ROOT, "%.9g", value); }
    private static String md(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("|", "\\|").replace("`", "\\`").replace("\r", " ").replace("\n", " ");
    }
    private static Map<String, Object> map(Object... items) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i += 2) result.put((String) items[i], items[i + 1]);
        return result;
    }
    /** Strict JSON encoder shared by the local workspace transport. */
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                    default -> { if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Cannot export a nonfinite report number");
            return value.toString();
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringJoiner out = new StringJoiner(",", "{", "}");
            map.forEach((key, item) -> out.add(encode(key.toString()) + ":" + encode(item)));
            return out.toString();
        }
        if (value instanceof Iterable<?> list) {
            StringJoiner out = new StringJoiner(",", "[", "]");
            for (Object item : list) out.add(encode(item)); return out.toString();
        }
        throw new IllegalArgumentException("Unsupported report value: " + value.getClass());
    }
}
