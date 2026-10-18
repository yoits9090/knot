package org.mathhacks.multibind;

import java.util.*;
import org.apache.commons.math3.linear.*;

/** Equality-constrained energy repair and explicitly bounded support enumeration. */
public final class Repairs {
    private Repairs() { }
    private record Arc(int to, double difference) { }
    public record Adjustment(int edge, double before, double after, double sigmaUnits,
                              double originalParameter, double repairedParameter,
                              double conditionalStandardError, boolean changed, boolean locked) { }

    public static final class Candidate {
        private final double[] energies;
        private final List<Adjustment> adjustments;
        private final double chiSquare;
        private Candidate(double[] energies, List<Adjustment> adjustments, double chiSquare) {
            this.energies = energies.clone(); this.adjustments = List.copyOf(adjustments); this.chiSquare = chiSquare;
        }
        public double energy(int i) { return energies[i]; }
        public List<Adjustment> adjustments() { return adjustments; }
        public double chiSquare() { return chiSquare; }
        public long changedCount() { return adjustments.stream().filter(Adjustment::changed).count(); }
    }

    public static final class InfeasibleLocks extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final transient CycleAnalysis.Witness witness;
        private InfeasibleLocks(CycleAnalysis.Witness witness) {
            super("Locked measurements do not close around a cycle; exact preservation is infeasible within numerical tolerance");
            this.witness = witness;
        }
        public CycleAnalysis.Witness witness() { return witness; }
    }

    /** Locks keep the supplied edge's converted energy exactly, up to documented floating-point tolerance. */
    public static Candidate solve(Multibind.Fit fit, Set<Integer> locked) {
        locked = Set.copyOf(locked);
        int n = fit.size(), m = fit.model().edges().size();
        for (int edge : locked) if (edge < 0 || edge >= m)
            throw new IllegalArgumentException("Locked edge is out of range: " + (edge + 1));
        var index = CycleAnalysis.indices(fit);
        List<List<Arc>> adjacency = new ArrayList<>();
        for (int i = 0; i < n; i++) adjacency.add(new ArrayList<>());
        int[] from = new int[m], to = new int[m];
        for (int e = 0; e < m; e++) {
            var edge = fit.model().edges().get(e);
            int i = index.get(edge.from()), j = index.get(edge.to());
            from[e] = i; to[e] = j;
            if (locked.contains(e)) {
                adjacency.get(i).add(new Arc(j, fit.inputDelta(e)));
                adjacency.get(j).add(new Arc(i, -fit.inputDelta(e)));
            }
        }
        int[] component = new int[n]; Arrays.fill(component, -1);
        double[] offset = new double[n];
        int components = 0;
        for (int root = 0; root < n; root++) {
            if (component[root] >= 0) continue;
            component[root] = components++;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(root);
            while (!queue.isEmpty()) {
                int i = queue.remove();
                for (Arc arc : adjacency.get(i)) {
                    double expected = offset[i] + arc.difference();
                    Model.finite(expected, "locked component offset");
                    if (component[arc.to()] < 0) {
                        component[arc.to()] = component[i]; offset[arc.to()] = expected; queue.add(arc.to());
                    } else if (Math.abs(offset[arc.to()] - expected)
                               > CycleAnalysis.tolerance(Math.abs(offset[arc.to()]) + Math.abs(expected))) {
                        for (var cycle : CycleAnalysis.basis(fit, locked)) {
                            var witness = CycleAnalysis.witness(fit, cycle, 1, 0.01);
                            if (witness.numericalDiscrepancy()) throw new InfeasibleLocks(witness);
                        }
                        throw new IllegalArgumentException("Locked graph is numerically unstable");
                    }
                }
            }
        }
        double[] x = new double[components];
        double[][] componentCovariance = new double[components][components];
        if (components > 1) {
            double maxWeight = 0;
            int crossEdges = 0;
            for (int e = 0; e < m; e++) if (component[from[e]] != component[to[e]]) {
                maxWeight = Math.max(maxWeight, 1 / fit.inputVariance(e)); crossEdges++;
            }
            double[][] design = new double[crossEdges][components];
            double[] observations = new double[crossEdges];
            int row = 0;
            for (int e = 0; e < m; e++) {
                int i = from[e], j = to[e], ci = component[i], cj = component[j];
                if (ci == cj) continue;
                double weight = Math.sqrt((1 / fit.inputVariance(e)) / maxWeight);
                design[row][ci] = -weight; design[row][cj] = weight;
                observations[row] = weight * (fit.inputDelta(e) - (offset[j] - offset[i]));
                row++;
            }
            var svd = new SingularValueDecomposition(new Array2DRowRealMatrix(design));
            double[] singular = svd.getSingularValues();
            if (singular[components - 2] <= singular[0] * Math.sqrt(1e-15))
                throw new IllegalArgumentException("Constrained graph is numerically unidentifiable");
            RealMatrix u = svd.getU(), v = svd.getV();
            for (int k = 0; k < components - 1; k++) {
                double projection = 0;
                for (int e = 0; e < crossEdges; e++) projection += u.getEntry(e, k) * observations[e];
                for (int i = 0; i < components; i++) {
                    x[i] += v.getEntry(i, k) * projection / singular[k];
                    for (int j = 0; j < components; j++)
                        componentCovariance[i][j] += v.getEntry(i, k) * v.getEntry(j, k)
                            / (singular[k] * singular[k]) / maxWeight;
                }
            }
        }
        double[] g = new double[n];
        double reference = offset[0] + x[component[0]];
        for (int i = 0; i < n; i++) {
            g[i] = offset[i] + x[component[i]] - reference;
            Model.finite(g[i], "constrained energy");
        }
        List<Adjustment> adjustments = new ArrayList<>();
        double chiSquare = 0;
        for (int e = 0; e < m; e++) {
            int ci = component[from[e]], cj = component[to[e]];
            double before = fit.inputDelta(e), after = g[to[e]] - g[from[e]], delta = after - before;
            double tolerance = CycleAnalysis.tolerance(Math.abs(before) + Math.abs(after));
            if (locked.contains(e) && Math.abs(delta) > tolerance)
                throw new IllegalArgumentException("Constrained solver failed lock verification at edge " + (e + 1));
            double sigmaUnits = delta / Math.sqrt(fit.inputVariance(e));
            chiSquare += sigmaUnits * sigmaUnits;
            double variance = componentCovariance[ci][ci] + componentCovariance[cj][cj]
                            - 2 * componentCovariance[ci][cj];
            var edge = fit.model().edges().get(e);
            double original = edge.value();
            double repaired = locked.contains(e) ? original : original +
                (edge.ligand().equalsIgnoreCase("H+") ? -delta / Math.log(10) : delta);
            adjustments.add(new Adjustment(e, before, after, sigmaUnits, original, repaired,
                Math.sqrt(Math.max(0, variance)), Math.abs(delta) > tolerance, locked.contains(e)));
        }
        Model.finite(chiSquare, "repair objective");
        return new Candidate(g, adjustments, chiSquare);
    }

    public record Search(List<Candidate> candidates, int supportsTested, int budget,
                          int exploredChangeLimit, Integer minimumChangedCount,
                          boolean minimumCardinalityCertified, boolean minimumLevelComplete,
                          boolean budgetExhausted, String status) {
        public Search { candidates = List.copyOf(candidates); }
    }

    /** Enumerates supports by increasing cardinality; only an exhausted lower level proves minimality. */
    public static Search alternatives(Multibind.Fit fit, Set<Integer> userLocks, int maxChanges,
                                       int budget, int keep) {
        if (maxChanges < 0 || maxChanges > fit.model().edges().size())
            throw new IllegalArgumentException("maxChanges must be between zero and the number of edges");
        if (budget < 1 || budget > 100_000 || keep < 1 || keep > 20)
            throw new IllegalArgumentException("Search budget must be 1..100000 and retained alternatives 1..20");
        // Distinguish a contradictory trust policy from a support-search failure.
        solve(fit, userLocks);
        List<Integer> pool = new ArrayList<>();
        for (int e = 0; e < fit.model().edges().size(); e++) if (!userLocks.contains(e)) pool.add(e);
        var enumeration = new Enumeration(fit, pool, budget, keep);
        for (int size = 0; size <= Math.min(maxChanges, pool.size()); size++) {
            enumeration.best.clear();
            enumeration.enumerate(size, 0, new HashSet<>());
            if (!enumeration.best.isEmpty()) {
                int actual = (int) enumeration.best.getFirst().changedCount();
                return new Search(enumeration.best, enumeration.tested, budget, size, actual,
                    actual == size, !enumeration.exhausted, enumeration.exhausted,
                    enumeration.exhausted ? "minimum size found; ranking covers evaluated supports only"
                                          : "minimum size found; all supports at that size evaluated");
            }
            if (enumeration.exhausted)
                return new Search(List.of(), enumeration.tested, budget, size, null, false, false, true,
                                  "budget exhausted; no sparse repair found, not a proof of infeasibility");
        }
        return new Search(List.of(), enumeration.tested, budget, Math.min(maxChanges, pool.size()), null,
                          false, true, false, "no repair within the evaluated change limit");
    }
    private static final class Enumeration {
        private final Multibind.Fit fit;
        private final List<Integer> pool;
        private final int budget, keep;
        private final List<Candidate> best = new ArrayList<>();
        private int tested;
        private boolean exhausted;
        private Enumeration(Multibind.Fit fit, List<Integer> pool, int budget, int keep) {
            this.fit = fit; this.pool = pool; this.budget = budget; this.keep = keep;
        }
        private void enumerate(int remaining, int start, Set<Integer> free) {
            if (exhausted) return;
            if (remaining == 0) {
                if (tested == budget) { exhausted = true; return; }
                tested++;
                Set<Integer> fixed = new HashSet<>();
                for (int e = 0; e < fit.model().edges().size(); e++) if (!free.contains(e)) fixed.add(e);
                try {
                    Candidate candidate = solve(fit, fixed);
                    best.add(candidate);
                    best.sort(Comparator.comparingDouble(Candidate::chiSquare));
                    if (best.size() > keep) best.removeLast();
                } catch (InfeasibleLocks expected) {
                    // This candidate support cannot close all cycles while preserving the other edges.
                }
                return;
            }
            for (int i = start; i <= pool.size() - remaining && !exhausted; i++) {
                free.add(pool.get(i)); enumerate(remaining - 1, i + 1, free); free.remove(pool.get(i));
            }
        }
    }
}
