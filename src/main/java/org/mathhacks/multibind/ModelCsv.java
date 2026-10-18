package org.mathhacks.multibind;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Author-compatible CSV input. Full-line # comments and quoted single-line fields are supported. */
public final class ModelCsv {
    private ModelCsv() { }
    public static Model read(Path statesFile, Path graphFile) throws IOException {
        List<Model.State> states = new ArrayList<>();
        for (Map<String, String> row : rows(statesFile)) {
            Map<String, String> categories = new LinkedHashMap<>(row);
            String name = required(row, "name"); categories.remove("name");
            states.add(new Model.State(name, categories));
        }
        List<Model.Edge> edges = new ArrayList<>();
        for (Map<String, String> row : rows(graphFile))
            edges.add(new Model.Edge(required(row, "state1"), required(row, "state2"), number(row, "value"),
                       number(row, "variance"), required(row, "ligand"), number(row, "standard_state")));
        return new Model(states, edges);
    }
    public static List<ThermodynamicRates.Rate> readRates(Path file) throws IOException {
        List<ThermodynamicRates.Rate> rates = new ArrayList<>();
        for (Map<String, String> row : rows(file)) {
            String variance = row.containsKey("var") ? "var" : "kvar";
            rates.add(new ThermodynamicRates.Rate(required(row, "state1"), required(row, "state2"),
                      number(row, "k"), number(row, variance)));
        }
        return List.copyOf(rates);
    }

    public static List<Map<String, String>> rows(Path file) throws IOException {
        List<String> header = null;
        List<Map<String, String>> result = new ArrayList<>();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file)) {
            lineNumber++;
            if (lineNumber == 1 && raw.startsWith("\ufeff")) raw = raw.substring(1);
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
            List<String> fields = fields(raw);
            if (header == null) {
                header = fields;
                if (new HashSet<>(header).size() != header.size() || header.stream().anyMatch(String::isBlank))
                    throw new IllegalArgumentException("Empty or duplicate CSV headers in " + file);
                continue;
            }
            if (fields.size() != header.size())
                throw new IllegalArgumentException("CSV column count at " + file + ":" + lineNumber);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), fields.get(i));
            result.add(Collections.unmodifiableMap(row));
        }
        if (header == null || result.isEmpty()) throw new IllegalArgumentException("Empty CSV: " + file);
        return List.copyOf(result);
    }
    private static List<String> fields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') { quoted = false; closed = true; }
                else field.append(c);
            } else if (c == ',') {
                fields.add(field.toString().strip()); field.setLength(0); closed = false;
            } else if (c == '"') {
                if (!field.toString().isBlank() || closed) throw new IllegalArgumentException("Misplaced CSV quote");
                field.setLength(0); quoted = true;
            } else {
                if (closed && !Character.isWhitespace(c)) throw new IllegalArgumentException("Text after CSV closing quote");
                field.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote; multiline fields are unsupported");
        fields.add(field.toString().strip());
        return fields;
    }
    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing CSV field: " + key);
        return value;
    }
    private static double number(Map<String, String> row, String key) {
        try { return Double.parseDouble(required(row, key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number in " + key, e); }
    }
}
