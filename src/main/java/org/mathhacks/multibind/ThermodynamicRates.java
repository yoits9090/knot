package org.mathhacks.multibind;

import java.util.*;

/** Two-stage rate projection, paper Eqs. 16-20 and Appendix A.3. */
public final class ThermodynamicRates {
    private ThermodynamicRates() { }

    public record Rate(String from, String to, double value, double variance) {
        public Rate {
            if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to))
                throw new IllegalArgumentException("Rates require distinct named states");
            Model.positive(value, "rate"); Model.finite(variance, "rate variance");
            if (variance < 0) throw new IllegalArgumentException("Rate variance must be nonnegative");
        }
    }
    public record Pair(double forward, double backward, double forwardPaperStandardError,
                       double backwardPaperStandardError) { }

    /**
     * Fixed-energy Gaussian rate projection. Variances are measurement variances.
     * Zero input sigmas use the authors' 1e-12 compatibility floor, NOT an exact lock.
     * Reported errors reproduce the authors' approximate propagation from energy errors.
     */
    public static Pair project(double forward, double backward, double forwardVariance,
                               double backwardVariance, double deltaG,
                               double stateIStandardError, double stateJStandardError) {
        Model.positive(forward, "forward rate"); Model.positive(backward, "backward rate");
        for (double x : new double[]{forwardVariance, backwardVariance, stateIStandardError, stateJStandardError}) {
            Model.finite(x, "variance or standard error");
            if (x < 0) throw new IllegalArgumentException("Variances and standard errors must be nonnegative");
        }
        Model.finite(deltaG, "target energy difference");
        double vf = forwardVariance == 0 ? 1e-24 : forwardVariance;
        double vb = backwardVariance == 0 ? 1e-24 : backwardVariance;
        double logK = -deltaG;
        double logOdds = Math.log(vb) - Math.log(vf) + 2 * logK;
        double logT = -softplus(-logOdds), logOneMinusT = -softplus(logOdds);
        // k_ji = (1-t)*input_backward + t*input_forward/K; log arithmetic avoids K^2 overflow.
        double logPart1 = logOneMinusT + Math.log(backward);
        double logPart2 = logT + Math.log(forward) - logK;
        double logB = logAdd(logPart1, logPart2);
        double b = Math.exp(logB), f = Math.exp(logB + logK);
        Model.positive(b, "projected reverse rate (outside representable range)");
        Model.positive(f, "projected forward rate (outside representable range)");
        double t = Math.exp(logT);
        double derivative = 2 * t * Math.exp(logPart1) + (2 * t - 1) * Math.exp(logPart2);
        double sdG = Math.hypot(stateIStandardError, stateJStandardError);
        double sb = Math.abs(derivative) * sdG;
        double sf = sdG * Math.hypot(f, (derivative / b) * f);
        Model.finite(sb, "projected reverse standard error");
        Model.finite(sf, "projected forward standard error");
        return new Pair(f, b, sf, sb);
    }
    private static double softplus(double x) { return Math.max(x, 0) + Math.log1p(Math.exp(-Math.abs(x))); }
    private static double logAdd(double a, double b) {
        double max = Math.max(a, b);
        return max + Math.log1p(Math.exp(Math.min(a, b) - max));
    }

    /** Input rates must represent a single compatible equilibrium/standard-state condition. */
    public static RateFit fit(List<Rate> rates) {
        if (rates.isEmpty()) throw new IllegalArgumentException("No rates provided");
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<List<String>, Rate> directed = new LinkedHashMap<>();
        for (Rate rate : rates) {
            index.computeIfAbsent(rate.from(), key -> index.size());
            index.computeIfAbsent(rate.to(), key -> index.size());
            if (directed.putIfAbsent(List.of(rate.from(), rate.to()), rate) != null)
                throw new IllegalArgumentException("Duplicate directed rate: " + rate.from() + " -> " + rate.to());
        }
        List<Model.Edge> edges = new ArrayList<>();
        List<Rate> forwardRates = new ArrayList<>(), backwardRates = new ArrayList<>();
        Set<Set<String>> processed = new HashSet<>();
        for (Rate forward : rates) {
            if (!processed.add(Set.of(forward.from(), forward.to()))) continue;
            Rate backward = directed.get(List.of(forward.to(), forward.from()));
            if (backward == null) throw new IllegalArgumentException("Missing reverse rate for " + forward);
            double delta = Math.log(backward.value()) - Math.log(forward.value());
            double variance = Math.pow(Math.sqrt(forward.variance()) / forward.value(), 2)
                            + Math.pow(Math.sqrt(backward.variance()) / backward.value(), 2);
            edges.add(Model.Edge.energy(forward.from(), forward.to(), delta, variance));
            forwardRates.add(forward); backwardRates.add(backward);
        }
        Model model = new Model(index.keySet().stream().map(Model.State::new).toList(), edges);
        Multibind.Fit potential = Multibind.fit(model);
        int n = index.size();
        double[][] matrix = new double[n][n], errors = new double[n][n];
        for (int e = 0; e < edges.size(); e++) {
            Rate f = forwardRates.get(e), b = backwardRates.get(e);
            int i = index.get(f.from()), j = index.get(f.to());
            Pair projected = project(f.value(), b.value(), f.variance(), b.variance(),
                                     potential.energyDifference(i, j), potential.paperStateStandardError(i),
                                     potential.paperStateStandardError(j));
            matrix[i][j] = projected.forward(); matrix[j][i] = projected.backward();
            errors[i][j] = projected.forwardPaperStandardError();
            errors[j][i] = projected.backwardPaperStandardError();
        }
        return new RateFit(potential, matrix, errors);
    }

    public static final class RateFit {
        private final Multibind.Fit potential;
        private final double[][] matrix, errors;
        private RateFit(Multibind.Fit potential, double[][] matrix, double[][] errors) {
            this.potential = potential; this.matrix = matrix; this.errors = errors;
        }
        public Multibind.Fit potential() { return potential; }
        /** Off-diagonal i,j means i -> j. Diagonal is zero, as in the reference (not a generator). */
        public double rate(int i, int j) { return matrix[i][j]; }
        public double paperStandardError(int i, int j) { return errors[i][j]; }
    }
}
