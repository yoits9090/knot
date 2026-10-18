package org.mathhacks.multibind;

import java.util.*;
import org.mathhacks.multibind.Model.Edge;
import org.mathhacks.multibind.Model.State;

/** Analytic controls, known corruptions, exact constraints, and bounded-search claims. */
public final class InvestigationTest {
    private static int assertions;
    private InvestigationTest() { }
    public static void main(String[] args) {
        cycles(); locks(); sparse(); seededNetworks();
        System.out.println("PASS: " + assertions + " investigation scientific assertions");
    }
    private static Model triangle(double third, double variance) {
        return new Model(List.of(new State("A"), new State("B"), new State("C")), List.of(
            Edge.energy("A", "B", 1, variance), Edge.energy("B", "C", 1, variance),
            Edge.energy("C", "A", third, variance)));
    }
    private static void cycles() {
        var clean = Multibind.fit(triangle(-2, 0.0004));
        var good = CycleAnalysis.analyze(clean, 0.01);
        check(good.witnesses().size() == 1, "triangle cycle rank");
        close(good.witnesses().getFirst().closure(), 0);
        check(!good.globalTension() && !good.witnesses().getFirst().numericalDiscrepancy(), "consistent control");
        var corrupt = Multibind.fit(triangle(-1, 0.0004));
        var analysis = CycleAnalysis.analyze(corrupt, 0.01);
        var witness = analysis.witnesses().getFirst();
        close(Math.abs(witness.closure()), 1);
        close(witness.standardError(), Math.sqrt(3 * 0.0004));
        check(witness.statisticallyFlagged() && analysis.globalTension(), "known corruption detected");
        check(witness.steps().size() == 3, "simple cycle witness");
        String current = witness.steps().getFirst().from(); double sum = 0;
        for (var step : witness.steps()) {
            check(step.from().equals(current), "witness is a traversable signed path");
            current = step.to(); sum += step.signedEnergy();
        }
        check(current.equals(witness.steps().getFirst().from()), "witness path closes");
        close(sum, witness.closure());
        close(analysis.chiSquare(), witness.zScore() * witness.zScore());
        var noisy = CycleAnalysis.analyze(Multibind.fit(triangle(-2 + 1e-6, 0.01)), 0.01);
        check(noisy.witnesses().getFirst().numericalDiscrepancy(), "means need not close exactly");
        check(!noisy.witnesses().getFirst().statisticallyFlagged() && !noisy.globalTension(), "ordinary noise is not a flagged contradiction");
        var tree = new Model(List.of(new State("A"), new State("B")), List.of(Edge.energy("A", "B", 5, 1)));
        var treeResult = CycleAnalysis.analyze(Multibind.fit(tree), 0.01);
        check(treeResult.witnesses().isEmpty() && treeResult.globalP() == null, "tree has no cycle test, not evidence of correct chemistry");
        rejects(() -> CycleAnalysis.analyze(clean, 0));
    }
    private static void locks() {
        var fit = Multibind.fit(triangle(-1, 0.0004));
        var unconstrained = Repairs.solve(fit, Set.of());
        close(unconstrained.chiSquare(), fit.chiSquare());
        for (int i = 0; i < fit.size(); i++) close(unconstrained.energy(i), fit.energy(i));
        var repaired = Repairs.solve(fit, Set.of(0, 1));
        close(repaired.adjustments().get(0).after(), 1);
        close(repaired.adjustments().get(1).after(), 1);
        close(repaired.adjustments().get(2).after(), -2);
        close(repaired.adjustments().get(2).repairedParameter(), -2);
        check(repaired.changedCount() == 1, "one unlocked edge changes");
        close(repaired.chiSquare(), 2500);
        for (var adjustment : repaired.adjustments()) close(adjustment.conditionalStandardError(), 0);
        try {
            Repairs.solve(fit, Set.of(0, 1, 2));
            throw new AssertionError("Inconsistent exact locks were accepted");
        } catch (Repairs.InfeasibleLocks expected) {
            check(expected.witness().steps().size() == 3, "lock contradiction carries its cycle");
        }
        var fixedControl = Repairs.solve(Multibind.fit(triangle(-2, 1)), Set.of(0, 1, 2));
        check(fixedControl.changedCount() == 0, "consistent all-locked control");
        var partial = Repairs.solve(fit, Set.of(0));
        close(partial.adjustments().get(0).conditionalStandardError(), 0);
        check(partial.adjustments().get(1).conditionalStandardError() > 0, "remaining component uncertainty is retained");
        close(partial.adjustments().get(1).after(), 0.5);
        close(partial.adjustments().get(2).after(), -1.5);
        var protonModel = new Model(fit.model().states(), List.of(
            new Edge("A", "B", 6, 0.01, "H+", 1),
            new Edge("B", "C", 6, 0.01, "H+", 1),
            Edge.energy("C", "A", 1, 0.01)));
        var protonFit = Multibind.fit(protonModel, 6, Map.of());
        var protonRepair = Repairs.solve(protonFit, Set.of(0, 2));
        close(protonRepair.adjustments().get(0).repairedParameter(), 6);
        close(protonRepair.adjustments().get(1).repairedParameter(), 6 + 1 / Math.log(10));
        // Conditional covariance has an analytic value on a two-state free graph.
        var twoState = Multibind.fit(new Model(List.of(new State("A"), new State("B")),
            List.of(Edge.energy("A", "B", 3, 0.25))));
        close(Repairs.solve(twoState, Set.of()).adjustments().getFirst().conditionalStandardError(), 0.5);
        rejects(() -> Repairs.solve(fit, Set.of(3)));
    }
    private static void sparse() {
        var fit = Multibind.fit(triangle(-1, 1));
        var search = Repairs.alternatives(fit, Set.of(), 2, 100, 3);
        check(search.minimumChangedCount() == 1 && search.minimumCardinalityCertified(), "minimum one-edge repair certified by exhausted zero-change level");
        check(search.minimumLevelComplete() && search.supportsTested() == 4, "all three one-edge alternatives evaluated");
        check(search.candidates().size() == 3, "three distinct explanations remain possible");
        for (var candidate : search.candidates()) {
            check(candidate.changedCount() == 1, "alternative modifies one edge");
            close(candidate.chiSquare(), 1);
            close(candidate.adjustments().stream().mapToDouble(Repairs.Adjustment::after).sum(), 0);
        }
        var constrained = Repairs.alternatives(fit, Set.of(0, 1), 2, 100, 3);
        check(constrained.candidates().size() == 1, "trust policy restricts alternatives");
        close(constrained.candidates().getFirst().adjustments().get(2).after(), -2);
        var exhausted = Repairs.alternatives(fit, Set.of(), 2, 1, 3);
        check(exhausted.budgetExhausted() && exhausted.candidates().isEmpty()
              && !exhausted.minimumCardinalityCertified(), "budget exhaustion makes no infeasibility/minimum claim");
        var partialRanking = Repairs.alternatives(fit, Set.of(), 2, 2, 3);
        check(partialRanking.budgetExhausted() && partialRanking.minimumCardinalityCertified()
              && !partialRanking.minimumLevelComplete(), "minimum size and incomplete ranking are distinct claims");
        var consistent = Repairs.alternatives(Multibind.fit(triangle(-2, 1)), Set.of(), 2, 100, 3);
        check(consistent.minimumChangedCount() == 0, "consistent data require zero changes");

        List<State> states = new ArrayList<>(); for (int i = 0; i < 6; i++) states.add(new State("S" + i));
        List<Edge> edges = List.of(Edge.energy("S0", "S1", 1, 1), Edge.energy("S1", "S2", 1, 1),
            Edge.energy("S2", "S0", -1, 1), Edge.energy("S2", "S3", 0, 1),
            Edge.energy("S3", "S4", 1, 1), Edge.energy("S4", "S5", 1, 1), Edge.energy("S5", "S3", -1, 1));
        var twoCycles = Multibind.fit(new Model(states, edges));
        var diagnostics = CycleAnalysis.analyze(twoCycles, 0.01);
        check(diagnostics.witnesses().size() == 2, "two independent cycles");
        for (var witness : diagnostics.witnesses())
            close(witness.adjustedP(), Math.min(1, 2 * witness.rawP()));
        var tooFew = Repairs.alternatives(twoCycles, Set.of(), 1, 100, 5);
        check(tooFew.candidates().isEmpty() && !tooFew.budgetExhausted(), "one change cannot repair two disjoint cycles");
        var sufficient = Repairs.alternatives(twoCycles, Set.of(), 2, 100, 20);
        check(sufficient.minimumChangedCount() == 2 && sufficient.minimumCardinalityCertified(), "two changes required");
        check(sufficient.candidates().size() == 9, "three choices in each independent triangle");
        rejects(() -> Repairs.alternatives(fit, Set.of(), 4, 100, 3));
        rejects(() -> Repairs.alternatives(fit, Set.of(), 2, 0, 3));
    }
    private static void seededNetworks() {
        Random random = new Random(20261018);
        for (int trial = 0; trial < 30; trial++) {
            int n = 4 + trial % 9;
            List<State> states = new ArrayList<>();
            double[] truth = new double[n];
            for (int i = 0; i < n; i++) { states.add(new State("N" + i)); truth[i] = 2 * random.nextGaussian(); }
            List<Edge> clean = new ArrayList<>();
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
                if (j != i + 1 && !random.nextBoolean()) continue;
                clean.add(Edge.energy("N" + i, "N" + j, truth[j] - truth[i], 0.04));
            }
            var consistent = Multibind.fit(new Model(states, clean));
            var diagnosis = CycleAnalysis.analyze(consistent, 0.01);
            check(!diagnosis.globalTension(), "seeded consistent control");
            check(diagnosis.witnesses().size() == clean.size() - n + 1, "cycle basis dimension");
            List<Edge> noisy = new ArrayList<>();
            double truthCost = 0;
            for (var edge : clean) {
                double perturbation = 0.2 * random.nextGaussian(); truthCost += perturbation * perturbation / edge.variance();
                noisy.add(Edge.energy(edge.from(), edge.to(), edge.value() + perturbation, edge.variance()));
            }
            var fit = Multibind.fit(new Model(states, noisy));
            var repaired = Repairs.solve(fit, Set.of());
            check(repaired.chiSquare() <= truthCost + 1e-8, "MLE objective is no worse than known feasible truth");
            for (int i = 0; i < n; i++) close(repaired.energy(i), fit.energy(i));
            var locked = Repairs.solve(fit, Set.of(0));
            close(locked.adjustments().getFirst().after(), fit.inputDelta(0));
            close(locked.adjustments().getFirst().conditionalStandardError(), 0);
            for (var witness : CycleAnalysis.analyze(fit, 0.01).witnesses()) {
                double correctedClosure = 0;
                for (var step : witness.steps()) correctedClosure += step.direction() * locked.adjustments().get(step.edge()).after();
                close(correctedClosure, 0);
            }
        }
    }
    private static void close(double actual, double expected) {
        assertions++;
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-9 + 1e-9 * Math.abs(expected))
            throw new AssertionError("expected " + expected + ", actual " + actual);
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        assertions++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid input was accepted");
    }
}
