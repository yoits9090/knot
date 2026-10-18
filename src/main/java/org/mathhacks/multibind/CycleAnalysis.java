package org.mathhacks.multibind;

import java.util.*;
import org.apache.commons.math3.special.Erf;
import org.apache.commons.math3.special.Gamma;

/** Signed witnesses in a deterministic spanning-tree cycle basis; not exhaustive simple-cycle search. */
public final class CycleAnalysis {
    private CycleAnalysis() { }

    /** Original edge index is zero-based in the API; reports display one-based indices. */
    public record Step(int edge, int direction, String from, String to, double signedEnergy) { }
    public record Witness(List<Step> steps, double closure, double standardError,
                          double zScore, double rawP, double adjustedP,
                          boolean numericalDiscrepancy, boolean statisticallyFlagged) {
        public Witness { steps = List.copyOf(steps); }
    }
    public record Analysis(List<Witness> witnesses, int degreesOfFreedom, double chiSquare,
                           Double globalP, double alpha) {
        public Analysis { witnesses = List.copyOf(witnesses); }
        public boolean globalTension() { return globalP != null && globalP < alpha; }
    }
    private record Arc(int to, int edge, int direction) { }

    public static Analysis analyze(Multibind.Fit fit, double alpha) {
        if (!(alpha > 0 && alpha < 1)) throw new IllegalArgumentException("alpha must be between zero and one");
        Set<Integer> all = new LinkedHashSet<>();
        for (int e = 0; e < fit.model().edges().size(); e++) all.add(e);
        List<List<Step>> basis = basis(fit, all);
        List<Witness> witnesses = new ArrayList<>();
        for (List<Step> steps : basis) witnesses.add(witness(fit, steps, basis.size(), alpha));
        witnesses.sort(Comparator.comparingDouble((Witness w) -> Math.abs(w.zScore())).reversed());
        int df = fit.degreesOfFreedom();
        Model.finite(fit.chiSquare(), "global measurement lack-of-fit statistic");
        Double globalP = df == 0 ? null : Gamma.regularizedGammaQ(df / 2.0, fit.chiSquare() / 2.0);
        return new Analysis(witnesses, df, fit.chiSquare(), globalP, alpha);
    }

    /** Also works on disconnected subsets, such as a user's locked-edge graph. */
    static List<List<Step>> basis(Multibind.Fit fit, Set<Integer> selected) {
        int n = fit.size();
        List<List<Arc>> adjacency = new ArrayList<>(), tree = new ArrayList<>();
        for (int i = 0; i < n; i++) { adjacency.add(new ArrayList<>()); tree.add(new ArrayList<>()); }
        Map<String, Integer> index = indices(fit);
        for (int e = 0; e < fit.model().edges().size(); e++) {
            if (!selected.contains(e)) continue;
            var edge = fit.model().edges().get(e);
            int i = index.get(edge.from()), j = index.get(edge.to());
            adjacency.get(i).add(new Arc(j, e, 1)); adjacency.get(j).add(new Arc(i, e, -1));
        }
        Set<Integer> treeEdges = new HashSet<>();
        boolean[] seen = new boolean[n];
        for (int root = 0; root < n; root++) {
            if (seen[root]) continue;
            seen[root] = true;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(root);
            while (!queue.isEmpty()) {
                int i = queue.remove();
                for (Arc arc : adjacency.get(i)) if (!seen[arc.to()]) {
                    seen[arc.to()] = true; queue.add(arc.to()); treeEdges.add(arc.edge());
                    tree.get(i).add(arc);
                    tree.get(arc.to()).add(new Arc(i, arc.edge(), -arc.direction()));
                }
            }
        }
        List<List<Step>> result = new ArrayList<>();
        for (int e = 0; e < fit.model().edges().size(); e++) {
            if (!selected.contains(e) || treeEdges.contains(e)) continue;
            var edge = fit.model().edges().get(e);
            int source = index.get(edge.from()), destination = index.get(edge.to());
            List<Step> cycle = new ArrayList<>();
            cycle.add(step(fit, e, 1));
            // Close the chord i->j with the unique tree path j->i.
            int[] parent = new int[n], parentEdge = new int[n], parentDirection = new int[n];
            Arrays.fill(parent, -1); parent[destination] = destination;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(destination);
            while (!queue.isEmpty() && parent[source] < 0) {
                int i = queue.remove();
                for (Arc arc : tree.get(i)) if (parent[arc.to()] < 0) {
                    parent[arc.to()] = i; parentEdge[arc.to()] = arc.edge();
                    parentDirection[arc.to()] = arc.direction(); queue.add(arc.to());
                }
            }
            List<Step> reverse = new ArrayList<>();
            for (int i = source; i != destination; i = parent[i])
                reverse.add(step(fit, parentEdge[i], parentDirection[i]));
            Collections.reverse(reverse); cycle.addAll(reverse);
            result.add(List.copyOf(cycle));
        }
        return List.copyOf(result);
    }

    static Witness witness(Multibind.Fit fit, List<Step> steps, int comparisons, double alpha) {
        double sum = 0, error = 0, scale = 0;
        for (Step step : steps) {
            sum += step.signedEnergy(); scale += Math.abs(step.signedEnergy());
            error = Math.hypot(error, Math.sqrt(fit.inputVariance(step.edge())));
        }
        double z = sum / error;
        Model.finite(sum, "cycle closure"); Model.finite(error, "cycle standard error");
        Model.finite(z, "cycle standardized discrepancy");
        double p = Erf.erfc(Math.abs(z) / Math.sqrt(2));
        double adjusted = Math.min(1, p * Math.max(1, comparisons)); // Bonferroni, no independence of cycles assumed.
        return new Witness(steps, sum, error, z, p, adjusted,
                           Math.abs(sum) > tolerance(scale), adjusted < alpha);
    }
    static double tolerance(double scale) { return 1e-10 + 1e-12 * Math.abs(scale); }
    static Map<String, Integer> indices(Multibind.Fit fit) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < fit.size(); i++) index.put(fit.model().states().get(i).name(), i);
        return index;
    }
    private static Step step(Multibind.Fit fit, int edgeIndex, int direction) {
        var edge = fit.model().edges().get(edgeIndex);
        return new Step(edgeIndex, direction, direction == 1 ? edge.from() : edge.to(),
                        direction == 1 ? edge.to() : edge.from(), direction * fit.inputDelta(edgeIndex));
    }
}
