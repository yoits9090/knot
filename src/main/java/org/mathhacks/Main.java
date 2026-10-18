package org.mathhacks;

import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import org.mathhacks.multibind.*;

/** Numerical CLI and local visual-workspace entry point. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("knot — thermodynamic model debugger");
            System.out.println("  energies STATES.csv GRAPH.csv [--ph VALUE] [--concentration NAME=VALUE]...");
            System.out.println("  rates RATES.csv");
            System.out.println("  serve [PORT]  # local JavaScript workspace, default 8080");
            System.out.println("  investigate STATES.csv GRAPH.csv --assume-equilibrium [options]");
            System.out.println("    --ph VALUE --concentration NAME=VALUE --lock 1,2 --alpha 0.01");
            System.out.println("    --max-changes 2 --search-budget 2000 --alternatives 3");
            System.out.println("    --format markdown|json --output REPORT_PATH");
            System.out.println("Energies are in kT; input variances are variances, not standard deviations.");
            System.out.println("Use compatible equilibrium/standard-state inputs with all relevant states included.");
            return;
        }
        try {
            if (args[0].equals("serve") && args.length <= 2) {
                WorkspaceServer.run(args.length == 2 ? Integer.parseInt(args[1]) : 8080);
            } else if (args[0].equals("investigate")) {
                int code = InvestigationCli.run(Arrays.copyOfRange(args, 1, args.length));
                if (code != 0) System.exit(code);
            } else if (args[0].equals("energies") && args.length >= 3) {
                double pH = 0;
                Map<String, Double> concentrations = new LinkedHashMap<>();
                for (int i = 3; i < args.length; i++) {
                    if (i + 1 == args.length) throw new IllegalArgumentException("Missing option value");
                    String option = args[i], value = args[++i];
                    if (option.equals("--ph")) pH = Double.parseDouble(value);
                    else if (option.equals("--concentration")) {
                        String[] pair = value.split("=", -1);
                        if (pair.length != 2) throw new IllegalArgumentException("Expected NAME=VALUE");
                        concentrations.put(pair[0], Double.parseDouble(pair[1]));
                    } else throw new IllegalArgumentException("Unknown option: " + option);
                }
                print(Multibind.fit(ModelCsv.read(Path.of(args[1]), Path.of(args[2])), pH, concentrations));
            } else if (args[0].equals("rates") && args.length == 2) {
                var result = ThermodynamicRates.fit(ModelCsv.readRates(Path.of(args[1])));
                print(result.potential());
                System.out.println("from,to,projected_rate,paper_approx_standard_error");
                var states = result.potential().model().states();
                for (int i = 0; i < states.size(); i++) for (int j = 0; j < states.size(); j++)
                    if (result.rate(i, j) > 0)
                        System.out.printf("%s,%s,%.17g,%.17g%n", csv(states.get(i).name()), csv(states.get(j).name()),
                                          result.rate(i, j), result.paperStandardError(i, j));
            } else throw new IllegalArgumentException("Invalid command; use --help");
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Input/analysis error: " + e.getMessage());
            System.exit(2);
        }
    }
    private static void print(Multibind.Fit fit) {
        System.out.println("state,energy_kT,paper_centered_standard_error,probability");
        for (int i = 0; i < fit.size(); i++)
            System.out.printf("%s,%.17g,%.17g,%.17g%n", csv(fit.model().states().get(i).name()),
                              fit.energy(i), fit.paperStateStandardError(i), fit.probability(i));
        System.out.println("from,to,input_delta_kT,fitted_delta_kT,residual_kT");
        for (int e = 0; e < fit.model().edges().size(); e++) {
            var edge = fit.model().edges().get(e);
            System.out.printf("%s,%s,%.17g,%.17g,%.17g%n", csv(edge.from()), csv(edge.to()), fit.inputDelta(e),
                              fit.inputDelta(e) + fit.edgeResidual(e), fit.edgeResidual(e));
        }
        System.out.printf("# chi_square=%.17g degrees_of_freedom=%d condition_number=%.17g%n",
                          fit.chiSquare(), fit.degreesOfFreedom(), fit.conditionNumber());
        if (fit.conditionNumber() > 1e8) System.err.println("Warning: ill-conditioned potential graph; numerical sensitivity is elevated");
    }
    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
