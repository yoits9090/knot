package org.mathhacks.multibind;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** A complete, UI-independent state-graph investigation. */
public final class Investigation {
    private Investigation() { }
    public record Options(double pH, Map<String, Double> concentrations, Set<Integer> lockedEdges,
                           double alpha, int maxChanges, int searchBudget, int alternatives) {
        public Options {
            concentrations = Map.copyOf(concentrations); lockedEdges = Set.copyOf(lockedEdges);
            Model.finite(pH, "pH");
            if (!(alpha > 0 && alpha < 1)) throw new IllegalArgumentException("alpha must be between zero and one");
            if (maxChanges < 0 || searchBudget < 1 || searchBudget > 100_000 || alternatives < 1 || alternatives > 20)
                throw new IllegalArgumentException("Invalid search limits: changes >= 0, budget 1..100000, alternatives 1..20");
        }
    }
    public record Result(String statesPath, String graphPath, String statesHash, String graphHash,
                          Options options, Multibind.Fit fit, CycleAnalysis.Analysis analysis,
                          Repairs.Candidate weightedRepair, Repairs.Search sparseSearch,
                          CycleAnalysis.Witness lockConflict) {
        public String status() {
            if (lockConflict != null) return "INFEASIBLE_LOCK_POLICY";
            if (analysis.degreesOfFreedom() == 0) return "NO_CYCLES_TO_TEST";
            return analysis.globalTension() ? "STATISTICAL_TENSION" : "NO_GLOBAL_TENSION_DETECTED";
        }
    }
    public static Result run(Path states, Path graph, Options options) throws IOException {
        String statesHash = sha256(states), graphHash = sha256(graph);
        Model model = ModelCsv.read(states, graph);
        var fit = Multibind.fit(model, options.pH(), options.concentrations());
        var analysis = CycleAnalysis.analyze(fit, options.alpha());
        Repairs.Candidate weighted = null; Repairs.Search sparse = null;
        CycleAnalysis.Witness conflict = null;
        try {
            weighted = Repairs.solve(fit, options.lockedEdges());
            sparse = Repairs.alternatives(fit, options.lockedEdges(), Math.min(options.maxChanges(), model.edges().size()),
                                          options.searchBudget(), options.alternatives());
        } catch (Repairs.InfeasibleLocks incompatible) { conflict = incompatible.witness(); }
        if (!statesHash.equals(sha256(states)) || !graphHash.equals(sha256(graph)))
            throw new IllegalArgumentException("Input files changed during investigation; retry on a stable snapshot");
        return new Result(states.toString(), graph.toString(), statesHash, graphHash, options,
                          fit, analysis, weighted, sparse, conflict);
    }
    private static String sha256(Path path) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
