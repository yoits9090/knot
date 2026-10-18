package org.mathhacks;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.mathhacks.multibind.*;

/** Loopback-only transport for the vanilla JavaScript workspace. No uploaded files are retained. */
public final class WorkspaceServer {
    private static final int BODY_LIMIT = 1_048_576;
    private final Path root;
    private final HttpServer server;
    private final String token = UUID.randomUUID().toString();
    private final Semaphore computation = new Semaphore(1);
    private final Map<String, String[]> examples = new LinkedHashMap<>();

    private WorkspaceServer(Path root, int port) throws IOException {
        this.root = root;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        examples.put("corrupted", new String[]{"samples/investigation/states.csv", "samples/investigation/corrupted.csv"});
        examples.put("consistent", new String[]{"samples/investigation/states.csv", "samples/investigation/consistent.csv"});
        if (Files.isRegularFile(root.resolve("examples/yiip-site-a/graph.csv")))
            examples.put("yiip", new String[]{"examples/yiip-site-a/states.csv", "examples/yiip-site-a/graph.csv"});
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public static void run(int port) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be 0..65535");
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve("workspace/index.html")))
            throw new IllegalArgumentException("Start the workspace from the repository root");
        var app = new WorkspaceServer(root, port);
        app.server.start();
        System.out.println("Workspace: http://127.0.0.1:" + app.server.getAddress().getPort());
        System.out.println("Press Ctrl+C to stop. Model uploads stay local and are discarded after analysis.");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            String host = exchange.getRequestHeaders().getFirst("Host");
            int port = server.getAddress().getPort();
            if (!(Objects.equals(host, "127.0.0.1:" + port) || Objects.equals(host, "localhost:" + port))) {
                send(exchange, 403, "text/plain", "Invalid local host"); return;
            }
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/api/investigate")) {
                    if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, "text/plain", "Use POST"); return; }
                    if (!token.equals(exchange.getRequestHeaders().getFirst("X-Workspace-Token"))) {
                        send(exchange, 403, "text/plain", "Invalid workspace token"); return;
                    }
                    if (!Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "")
                            .startsWith("application/x-www-form-urlencoded")) {
                        send(exchange, 415, "text/plain", "Expected form-encoded model"); return;
                    }
                    byte[] body = exchange.getRequestBody().readNBytes(BODY_LIMIT + 1);
                    if (body.length > BODY_LIMIT) { send(exchange, 413, "text/plain", "Model upload exceeds 1 MiB"); return; }
                    if (!computation.tryAcquire()) { send(exchange, 409, "text/plain", "Another analysis is running. Try again shortly."); return; }
                    try { investigate(exchange, form(new String(body, StandardCharsets.UTF_8))); }
                    finally { computation.release(); }
                } else if (!exchange.getRequestMethod().equals("GET")) {
                    send(exchange, 405, "text/plain", "Use GET");
                } else if (path.equals("/api/config")) {
                    json(exchange, Map.of("token", token, "examples", examples.keySet()));
                } else if (path.equals("/api/example")) {
                    String name = form(Objects.toString(exchange.getRequestURI().getRawQuery(), "")).get("name");
                    String[] files = examples.get(name);
                    if (files == null) { send(exchange, 404, "text/plain", "Unknown example"); return; }
                    json(exchange, Map.of("states", Files.readString(root.resolve(files[0])), "graph", Files.readString(root.resolve(files[1])),
                        "statesName", name + "-states.csv", "graphName", name + "-graph.csv"));
                } else {
                    String file = switch (path) {
                        case "/", "/index.html" -> "index.html";
                        case "/app.js" -> "app.js";
                        case "/style.css" -> "style.css";
                        default -> null;
                    };
                    if (file == null) { send(exchange, 404, "text/plain", "Not found"); return; }
                    String type = file.endsWith("js") ? "text/javascript" : file.endsWith("css") ? "text/css" : "text/html";
                    send(exchange, 200, type, Files.readString(root.resolve("workspace").resolve(file)));
                }
            } catch (IllegalArgumentException e) { send(exchange, 400, "text/plain", e.getMessage()); }
              catch (IOException e) { send(exchange, 500, "text/plain", "Unable to read or analyze the local model"); }
        }
    }

    private void investigate(HttpExchange exchange, Map<String, String> form) throws IOException {
        if (!"true".equals(form.get("equilibrium")))
            throw new IllegalArgumentException("Confirm compatible equilibrium/standard-state conditions before analysis");
        Path directory = Files.createTempDirectory("model-workspace-");
        Path states = directory.resolve("states.csv"), graph = directory.resolve("graph.csv");
        try {
            Files.writeString(states, required(form, "states")); Files.writeString(graph, required(form, "graph"));
            Model model = ModelCsv.read(states, graph);
            if (model.states().size() > 200 || model.edges().size() > 500)
                throw new IllegalArgumentException("The visual workspace supports at most 200 states and 500 transitions; use the CLI for larger models");
            Map<String, Double> concentrations = new LinkedHashMap<>();
            for (String item : form.getOrDefault("concentrations", "").split("[\\s,]+")) {
                if (item.isBlank()) continue;
                String[] pair = item.split("=", -1);
                if (pair.length != 2 || pair[0].isBlank()) throw new IllegalArgumentException("Use NAME=VALUE concentrations, separated by spaces");
                if (concentrations.put(pair[0], Double.parseDouble(pair[1])) != null)
                    throw new IllegalArgumentException("Duplicate concentration: " + pair[0]);
            }
            Set<Integer> locks = new TreeSet<>();
            if (!form.getOrDefault("locks", "").isBlank())
                for (String item : form.get("locks").split(",", -1)) locks.add(Integer.parseInt(item) - 1);
            int changes = Integer.parseInt(form.getOrDefault("maxChanges", "2"));
            int budget = Integer.parseInt(form.getOrDefault("budget", "2000"));
            if (changes > 4 || budget > 10000) throw new IllegalArgumentException("Workspace search limits: at most 4 changes and 10000 support evaluations");
            var options = new Investigation.Options(Double.parseDouble(form.getOrDefault("pH", "0")), concentrations, locks,
                Double.parseDouble(form.getOrDefault("alpha", "0.01")), changes, budget, 5);
            var r = Investigation.run(states, graph, options);
            // Preserve content hashes, but report upload names instead of ephemeral filesystem paths.
            r = new Investigation.Result(filename(form.get("statesName"), "states.csv"), filename(form.get("graphName"), "graph.csv"),
                r.statesHash(), r.graphHash(), r.options(), r.fit(), r.analysis(), r.weightedRepair(), r.sparseSearch(), r.lockConflict());
            json(exchange, Map.of("report", InvestigationReport.data(r), "markdown", InvestigationReport.markdown(r)));
        } finally {
            Files.deleteIfExists(states); Files.deleteIfExists(graph); Files.deleteIfExists(directory);
        }
    }

    private static String filename(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.replace('\\', '/').replaceAll("^.*/", "");
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key + " CSV");
        return value;
    }
    private static Map<String, String> form(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : text.split("&")) {
            if (field.isEmpty()) continue;
            String[] pair = field.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if (values.put(key, URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8)) != null)
                throw new IllegalArgumentException("Duplicate field: " + key);
        }
        return values;
    }
    private static void json(HttpExchange exchange, Object data) throws IOException {
        send(exchange, 200, "application/json", InvestigationReport.encode(data));
    }
    private static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
    }
}
