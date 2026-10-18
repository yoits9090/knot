#!/usr/bin/env python3
"""Local HTTP/core integration tests; standard-library only, no downloaded data required."""
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CLASSPATH = "build/classes:build/deps/commons-math3-3.6.1.jar"
checks = 0


def check(condition, description):
    global checks
    assert condition, description
    checks += 1


process = subprocess.Popen(
    ["java", "-cp", CLASSPATH, "org.mathhacks.Main", "serve", "0"], cwd=ROOT,
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
)
try:
    line = process.stdout.readline().strip()
    assert line.startswith("Workspace: http://127.0.0.1:"), line
    base = line.removeprefix("Workspace: ")

    def request(path, data=None, headers=None, method=None):
        req = urllib.request.Request(base + path, data=data, headers=headers or {}, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return response.status, response.read().decode(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode(), error.headers

    status, html, headers = request("/")
    check(status == 200 and 'id="network"' in html, "workspace HTML")
    check("frame-ancestors 'none'" in headers["Content-Security-Policy"], "local workspace CSP")
    for path, content in [("/app.js", "function analyze"), ("/style.css", ":root")]:
        status, text, _ = request(path)
        check(status == 200 and content in text, path)
    for path in ["/AGENTS.md", "/.git/config", "/examples/yiip-site-a/graph.csv", "/../README.md"]:
        check(request(path)[0] == 404, "no arbitrary filesystem access: " + path)
    check(request("/api/investigate")[0] == 405, "analysis requires POST")
    check(request("/", method="DELETE")[0] == 405, "static routes are read only")
    check(request("/", headers={"Host": "attacker.invalid"})[0] == 403, "reject foreign host")

    config = json.loads(request("/api/config")[1])
    check(set(config["examples"]) >= {"consistent", "corrupted"}, "synthetic examples available")
    token_headers = {"X-Workspace-Token": config["token"], "Content-Type": "application/x-www-form-urlencoded"}
    example = json.loads(request("/api/example?name=corrupted")[1])
    check(request("/api/example?name=../../README.md")[0] == 404, "example allowlist")
    check(request("/api/example?name=consistent&name=corrupted")[0] == 400, "reject ambiguous form fields")
    form = dict(example, equilibrium="true", pH="0", concentrations="", locks="", maxChanges="2", budget="2000")

    def analyze(values):
        return request("/api/investigate", urllib.parse.urlencode(values).encode(), token_headers)

    check(request("/api/investigate", b"states=x")[0] == 403, "analysis requires local token")
    check(request("/api/investigate", b"{}", {"X-Workspace-Token": config["token"], "Content-Type": "application/json"})[0] == 415, "explicit transport format")
    check(request("/api/investigate", b"x" * 1_048_577, token_headers)[0] == 413, "upload size bounded")
    check(analyze({**form, "equilibrium": "false"})[0] == 400, "assumptions required")
    for key, value in [("pH", "NaN"), ("maxChanges", "5"), ("budget", "10001"), ("locks", "4"), ("graph", "invalid"), ("concentrations", "Zn=1 Zn=2")]:
        check(analyze({**form, key: value})[0] == 400, "invalid " + key)
    large_states = "name\n" + "".join(f"S{i}\n" for i in range(201))
    large_graph = "state1,state2,value,variance,ligand,standard_state\n" + "".join(
        f"S{i},S{i+1},1,0.01,helm,1\n" for i in range(200))
    check(analyze({**form, "states": large_states, "graph": large_graph})[0] == 400, "state-count limit before SVD")

    status, text, _ = analyze(form)
    data = json.loads(text)
    report = data["report"]
    check(status == 200 and report["status"] == "STATISTICAL_TENSION", "detect synthetic corruption")
    check(len(report["cycle_witnesses"]) == 1, "signed cycle")
    check(abs(abs(report["cycle_witnesses"][0]["closure_kT"]) - 1) < 1e-12, "cycle closure magnitude")
    check(len(report["sparse_search"]["alternatives"]) == 3, "three alternative repairs")
    check(report["inputs"]["states_sha256"] == hashlib.sha256(form["states"].encode()).hexdigest(), "states content hash")
    check(report["inputs"]["graph_sha256"] == hashlib.sha256(form["graph"].encode()).hexdigest(), "graph content hash")
    check("model-workspace-" not in text, "exports use logical upload names")
    check("# Model investigation" in data["markdown"] and "Sparse alternatives" in data["markdown"], "complete Markdown export")

    # HTTP workflow must return the exact same numerical report as the tested CLI.
    cli = json.loads(subprocess.check_output([
        "java", "-cp", CLASSPATH, "org.mathhacks.Main", "investigate",
        "samples/investigation/states.csv", "samples/investigation/corrupted.csv",
        "--assume-equilibrium", "--alternatives", "5", "--format", "json",
    ], cwd=ROOT, text=True))
    for key in report:
        if key != "inputs":
            check(report[key] == cli[key], "HTTP/CLI equality: " + key)

    report = json.loads(analyze({**form, "locks": "1,2"})[1])["report"]
    values = report["weighted_repair"]["adjustments"]
    check(values[0]["repaired_parameter"] == 1 and values[1]["repaired_parameter"] == 1, "preserve trusted parameters")
    check(abs(values[2]["repaired_parameter"] + 2) < 1e-12, "locks select third-edge repair")
    check(report["sparse_search"]["minimum_cardinality_certified"], "minimum-size claim")
    report = json.loads(analyze({**form, "locks": "1,2,3"})[1])["report"]
    check(report["status"] == "INFEASIBLE_LOCK_POLICY", "incompatible locks still return inspectable report")
    check(report["weighted_repair"] is None and report["lock_conflict"] is not None, "no repair for conflicting locks")

    consistent = json.loads(request("/api/example?name=consistent")[1])
    report = json.loads(analyze({**form, **consistent})[1])["report"]
    check(report["status"] == "NO_GLOBAL_TENSION_DETECTED", "consistent control")
    check(report["weighted_repair"]["changed_count"] == 0, "consistent control unchanged")
    report = json.loads(analyze({**form, "budget": "1"})[1])["report"]
    check(report["sparse_search"]["budget_exhausted"] and not report["sparse_search"]["minimum_cardinality_certified"], "search bounds retained")

    if "yiip" in config["examples"]:
        yiip = json.loads(request("/api/example?name=yiip")[1])
        status, text, _ = analyze({**form, **yiip, "pH": "6.5", "concentrations": "Zn=0.000001", "maxChanges": "0"})
        report = json.loads(text)["report"]
        check(status == 200 and len(report["states"]) == 32 and len(report["edges"]) == 80, "published YiiP graph")
    print(f"PASS: {checks} workspace HTTP/core checks")
finally:
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
