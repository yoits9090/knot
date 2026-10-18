package org.mathhacks.multibind;

import java.util.*;

/** A connected potential graph. Energies are dimensionless beta*G (kT units). */
public record Model(List<State> states, List<Edge> edges) {
    public record State(String name, Map<String, String> categories) {
        public State {
            requireName(name);
            categories = Map.copyOf(categories);
        }
        public State(String name) { this(name, Map.of()); }
    }

    /** variance is in pKa^2 for H+ edges, otherwise in (beta*G)^2. */
    public record Edge(String from, String to, double value, double variance,
                       String ligand, double standardState) {
        public Edge {
            requireName(from);
            requireName(to);
            requireName(ligand);
            if (from.equals(to)) throw new IllegalArgumentException("Self transition: " + from);
            finite(value, "edge value");
            positive(variance, "edge variance (zero is not an exact-lock interface)");
            positive(standardState, "standard-state concentration");
        }
        public static Edge energy(String from, String to, double value, double variance) {
            return new Edge(from, to, value, variance, "helm", 1);
        }
    }

    public Model {
        states = List.copyOf(states);
        edges = List.copyOf(edges);
        if (states.isEmpty()) throw new IllegalArgumentException("At least one state is required");
        Set<String> names = new HashSet<>();
        for (State state : states) {
            if (!names.add(state.name())) throw new IllegalArgumentException("Duplicate state: " + state.name());
        }
        Set<Set<String>> pairs = new HashSet<>();
        for (Edge edge : edges) {
            if (!names.contains(edge.from()) || !names.contains(edge.to()))
                throw new IllegalArgumentException("Unknown state in edge: " + edge);
            if (!pairs.add(Set.of(edge.from(), edge.to())))
                throw new IllegalArgumentException("Duplicate state pair; combine replicate measurements explicitly: " + edge);
        }
        Set<String> reached = new HashSet<>();
        reached.add(states.getFirst().name());
        boolean changed;
        do {
            changed = false;
            for (Edge edge : edges) {
                if (reached.contains(edge.from())) changed |= reached.add(edge.to());
                if (reached.contains(edge.to())) changed |= reached.add(edge.from());
            }
        } while (changed);
        if (reached.size() != states.size())
            throw new IllegalArgumentException("Disconnected graph: relative energies between components are unidentified");
    }

    static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
    static void positive(double value, String name) {
        if (!(value > 0) || !Double.isFinite(value))
            throw new IllegalArgumentException(name + " must be finite and positive");
    }
    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Names must be nonempty");
    }
}
