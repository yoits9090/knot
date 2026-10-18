package org.mathhacks;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.mathhacks.multibind.*;

/** CLI boundary: explicit assumptions, one-based edge locks, and report export. */
public final class InvestigationCli {
    private InvestigationCli() { }
    public static int run(String[] args) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException("investigate requires STATES.csv GRAPH.csv --assume-equilibrium");
        Path states = Path.of(args[0]), graph = Path.of(args[1]), output = null;
        double pH = 0, alpha = 0.01;
        int maxChanges = 2, budget = 2000, alternatives = 3;
        String format = "markdown";
        boolean equilibrium = false;
        Map<String, Double> concentrations = new LinkedHashMap<>();
        Set<Integer> locked = new TreeSet<>();
        for (int i = 2; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--assume-equilibrium")) { equilibrium = true; continue; }
            if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++i];
            switch (option) {
                case "--ph" -> pH = Double.parseDouble(value);
                case "--alpha" -> alpha = Double.parseDouble(value);
                case "--max-changes" -> maxChanges = Integer.parseInt(value);
                case "--search-budget" -> budget = Integer.parseInt(value);
                case "--alternatives" -> alternatives = Integer.parseInt(value);
                case "--format" -> format = value;
                case "--output" -> output = Path.of(value);
                case "--lock" -> {
                    for (String edge : value.split(",", -1)) locked.add(Integer.parseInt(edge) - 1);
                }
                case "--concentration" -> {
                    String[] pair = value.split("=", -1);
                    if (pair.length != 2 || pair[0].isBlank()) throw new IllegalArgumentException("Expected NAME=VALUE concentration");
                    concentrations.put(pair[0], Double.parseDouble(pair[1]));
                }
                default -> throw new IllegalArgumentException("Unknown investigate option: " + option);
            }
        }
        if (!equilibrium) throw new IllegalArgumentException("Declare --assume-equilibrium only for compatible equilibrium/standard-state inputs; driven models are unsupported");
        if (!format.equals("json") && !format.equals("markdown")) throw new IllegalArgumentException("Report format must be json or markdown");
        var result = Investigation.run(states, graph,
            new Investigation.Options(pH, concentrations, locked, alpha, maxChanges, budget, alternatives));
        String report = format.equals("json") ? InvestigationReport.json(result) : InvestigationReport.markdown(result);
        if (output == null) System.out.print(report);
        else {
            Path absolute = output.toAbsolutePath().normalize();
            if (absolute.equals(states.toAbsolutePath().normalize()) || absolute.equals(graph.toAbsolutePath().normalize())
                || (Files.exists(absolute) && (Files.isSameFile(absolute, states) || Files.isSameFile(absolute, graph))))
                throw new IllegalArgumentException("Report output must not overwrite an input model file");
            Files.createDirectories(absolute.getParent()); Files.writeString(absolute, report);
            System.err.println("Wrote " + format + " investigation to " + output);
        }
        return result.lockConflict() == null ? 0 : 3;
    }
}
