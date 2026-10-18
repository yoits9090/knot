package org.mathhacks.multibind;

import java.util.*;
import org.apache.commons.math3.linear.*;

/** Gaussian potential-graph MLE, Kenney & Beckstein (2023), Eqs. 9-11. */
public final class Multibind {
    private Multibind() { }
    private static final double LN10 = Math.log(10);

    public static double protonationEnergy(double pKa, double pH) {
        Model.finite(pKa, "pKa"); Model.finite(pH, "pH");
        return LN10 * (pH - pKa);
    }

    public static double bindingEnergy(double standardEnergy, double concentration, double standardState) {
        Model.finite(standardEnergy, "standard energy");
        Model.positive(concentration, "ligand concentration");
        Model.positive(standardState, "standard-state concentration");
        return standardEnergy - (Math.log(concentration) - Math.log(standardState));
    }

    public static Fit fit(Model model, double pH, Map<String, Double> concentrations) {
        Model.finite(pH, "pH");
        concentrations.forEach((name, value) -> Model.positive(value, "concentration of " + name));
        int n = model.states().size(), m = model.edges().size();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(model.states().get(i).name(), i);
        double[] deltas = new double[m], variances = new double[m];
        int[] from = new int[m], to = new int[m];
        double maxWeight = 0;
        for (int e = 0; e < m; e++) {
            Model.Edge edge = model.edges().get(e);
            from[e] = index.get(edge.from()); to[e] = index.get(edge.to());
            if (edge.ligand().equalsIgnoreCase("H+")) {
                deltas[e] = protonationEnergy(edge.value(), pH);
                variances[e] = edge.variance() * LN10 * LN10;
            } else if (edge.ligand().equals("helm")) {
                deltas[e] = edge.value(); variances[e] = edge.variance();
            } else {
                Double concentration = concentrations.get(edge.ligand());
                if (concentration == null)
                    throw new IllegalArgumentException("Missing concentration for " + edge.ligand());
                deltas[e] = bindingEnergy(edge.value(), concentration, edge.standardState());
                variances[e] = edge.variance();
            }
            Model.finite(deltas[e], "converted free energy");
            Model.positive(variances[e], "converted variance");
            Model.positive(1 / variances[e], "inverse variance");
            maxWeight = Math.max(maxWeight, 1 / variances[e]);
        }
        double[][] covariance = new double[n][n];
        double[] energies = new double[n];
        double condition = 1;
        if (n > 1) {
            // Solve the square-root information system instead of squaring its condition
            // number in normal equations. B^+ y gives the same MLE as (B^T B)^+ B^T y;
            // B^+ (B^+)^T is the paper's Fisher pseudoinverse covariance.
            double[][] design = new double[m][n];
            double[] observations = new double[m];
            for (int e = 0; e < m; e++) {
                int i = from[e], j = to[e];
                double w = Math.sqrt((1 / variances[e]) / maxWeight);
                design[e][i] = -w; design[e][j] = w;
                observations[e] = w * deltas[e];
            }
            SingularValueDecomposition svd = new SingularValueDecomposition(new Array2DRowRealMatrix(design));
            double[] singular = svd.getSingularValues();
            double cutoff = singular[0] * Math.sqrt(1e-15); // Same rank boundary as pinv(B^T B).
            if (singular[n - 2] <= cutoff)
                throw new IllegalArgumentException("Graph is numerically unidentifiable at relative cutoff 1e-15");
            condition = Math.pow(singular[0] / singular[n - 2], 2);
            RealMatrix u = svd.getU(), v = svd.getV();
            // Exactly n-1 modes: the global energy offset is the known null direction.
            for (int k = 0; k < n - 1; k++) {
                double projected = 0;
                for (int e = 0; e < m; e++) projected += u.getEntry(e, k) * observations[e];
                for (int i = 0; i < n; i++) {
                    energies[i] += v.getEntry(i, k) * projected / singular[k];
                    for (int j = 0; j < n; j++)
                        covariance[i][j] += v.getEntry(i, k) * v.getEntry(j, k) / (singular[k] * singular[k]) / maxWeight;
                }
            }
            double reference = energies[0];
            for (int i = 0; i < n; i++) {
                energies[i] -= reference;
                Model.finite(energies[i], "fitted energy");
                for (int j = 0; j < i; j++) {
                    double symmetric = (covariance[i][j] + covariance[j][i]) / 2;
                    covariance[i][j] = symmetric; covariance[j][i] = symmetric;
                }
            }
        }
        return new Fit(model, energies, covariance, deltas, variances, from, to, condition);
    }

    public static Fit fit(Model model) { return fit(model, 0, Map.of()); }

    public record MacrostateDifference(double energy, double paperStandardError,
                                       double covarianceStandardError) { }

    /** Immutable outputs. Covariance uses the authors' mean-zero pseudoinverse gauge. */
    public static final class Fit {
        private final Model model;
        private final double[] energies, probabilities, inputDeltas, variances;
        private final double[][] covariance;
        private final int[] from, to;
        private final double condition;

        private Fit(Model model, double[] energies, double[][] covariance, double[] deltas,
                    double[] variances, int[] from, int[] to, double condition) {
            this.model = model; this.energies = energies; this.covariance = covariance;
            this.inputDeltas = deltas; this.variances = variances;
            this.from = from; this.to = to; this.condition = condition;
            this.probabilities = new double[energies.length];
            double min = Arrays.stream(energies).min().orElseThrow();
            double sum = 0;
            for (int i = 0; i < energies.length; i++) sum += probabilities[i] = Math.exp(-(energies[i] - min));
            for (int i = 0; i < energies.length; i++) probabilities[i] /= sum;
        }
        public Model model() { return model; }
        public int size() { return energies.length; }
        public double energy(int i) { return energies[i]; }
        public double probability(int i) { return probabilities[i]; }
        public double centeredCovariance(int i, int j) { return covariance[i][j]; }
        public double referenceCovariance(int i, int j) {
            return covariance[i][j] - covariance[i][0] - covariance[0][j] + covariance[0][0];
        }
        public double paperStateStandardError(int i) { return Math.sqrt(Math.max(0, covariance[i][i])); }
        public double energyDifference(int i, int j) { return energies[j] - energies[i]; }
        public double differenceStandardError(int i, int j) {
            return Math.sqrt(Math.max(0, covariance[i][i] + covariance[j][j] - 2 * covariance[i][j]));
        }
        public double inputDelta(int e) { return inputDeltas[e]; }
        public double inputVariance(int e) { return variances[e]; }
        public double edgeResidual(int e) { return energyDifference(from[e], to[e]) - inputDeltas[e]; }
        public double chiSquare() {
            double value = 0;
            for (int e = 0; e < from.length; e++) value += Math.pow(edgeResidual(e), 2) / variances[e];
            return value;
        }
        public int degreesOfFreedom() { return from.length - size() + 1; }
        public double conditionNumber() { return condition; }

        public MacrostateDifference macrostateDifference(String category, String source, String destination) {
            double[] a = macroWeights(category, source), b = macroWeights(category, destination);
            if (source.equals(destination)) return new MacrostateDifference(0, 0, 0);
            double logZa = a[size()], logZb = b[size()];
            double paperVariance = 0, fullVariance = 0;
            for (int i = 0; i < size(); i++) {
                paperVariance += covariance[i][i] * (a[i] * a[i] + b[i] * b[i]);
                for (int j = 0; j < size(); j++)
                    fullVariance += (b[i] - a[i]) * covariance[i][j] * (b[j] - a[j]);
            }
            return new MacrostateDifference(logZa - logZb, Math.sqrt(Math.max(0, paperVariance)),
                                            Math.sqrt(Math.max(0, fullVariance)));
        }
        private double[] macroWeights(String category, String label) {
            List<Integer> selected = new ArrayList<>();
            for (int i = 0; i < size(); i++)
                if (label.equals(model.states().get(i).categories().get(category))) selected.add(i);
            if (selected.isEmpty()) throw new IllegalArgumentException("Empty or unknown macrostate " + category + "=" + label);
            double min = selected.stream().mapToDouble(i -> energies[i]).min().orElseThrow();
            double[] weights = new double[size() + 1];
            double sum = 0;
            for (int i : selected) sum += weights[i] = Math.exp(-(energies[i] - min));
            for (int i : selected) weights[i] /= sum;
            weights[size()] = -min + Math.log(sum);
            return weights;
        }
    }
}
