# Visual investigation workspace

Run `make workspace` from the repository root, then open
http://127.0.0.1:8080. Java 21, Make, and the existing checksum-verified
Commons Math dependency are sufficient. The browser interface uses plain
JavaScript and native HTML/SVG, with one small stylesheet and no npm packages.
Use `make workspace PORT=8081` if the default port is occupied.

## Workflow

1. Load a synthetic example, or choose the states/transition CSV files and
   inspect or edit their text. CSV formats are the same as the energy CLI.
   If `make data` has prepared the published YiiP tables locally, that model
   also appears in the example menu with pH 6.5 and Zn concentration 1e-6.
2. Set pH/concentrations and explicitly confirm compatible equilibrium or
   standard-state inputs with complete relevant molecular states. The app
   cannot infer that the assertion holds scientifically.
3. Analyze. The network shows the selected candidate's converted energy
   differences in kT. Edges are reversible; arrows indicate the sign
   reference, not net physical flow. Select an edge to inspect its input
   parameter and variance. Drag nodes to rearrange, drag the background to
   pan, and use +/- or Reset layout to adjust the view.
4. Choose a cycle witness. The explanation shows original signed terms,
   closure, propagated standard error, z-score, adjusted p-value, and, when a
   candidate is selected, its repaired cycle sum. Numerical nonclosure and
   statistical tension remain distinct.
5. Compare the original measurements, weighted repair, and up to five bounded
   sparse alternatives. The parameter table shows original/candidate values
   in their original units, adjustments in converted-energy standard errors,
   and conditional candidate uncertainty. Very large/small displayed values
   use scientific notation. Search bounds and minimum-cardinality claims
   remain visible.
6. Lock parameters using their checkboxes. Every lock change reruns the Java
   investigation. Exact-lock conflicts return an inspectable signed witness;
   unlock an edge or use Unlock all to resume repair comparisons. Selecting
   a repair changes the preview; it never overwrites the input model.
7. Download JSON or Markdown. Exports contain the full calculated investigation,
   assumptions, hashes, lock policy, and all returned alternatives, regardless
   of which candidate/cycle is currently selected. They are reports, not
   replacement model files. Editing model inputs invalidates the report and
   disables export until analysis succeeds again.

The supported scientific scope is unchanged from
[the CLI investigation](investigation.md). This is a molecular-state energy
graph workspace, not a general SBML importer, reaction-composition validator,
directed-rate lock editor, or independent experimental-data fitting tool.
Costs measure adjustment relative to supplied input uncertainties. A candidate
with zero cycle closure is not proof of correct chemistry.

## Local transport

`WorkspaceServer` uses the JDK HTTP server, bound to `127.0.0.1`. It serves
only the three workspace assets and fixed example routes. A per-process
request token and local Host allowlist protect the analysis endpoint. No
external scripts, fonts, or services are loaded. The application does not
send model data outside the local machine.

The browser sends CSV text and options, not filesystem paths. Each request
gets isolated temporary input files, removed after analysis, and the report
uses logical upload filenames with SHA-256 content hashes. Exports are browser
downloads; the server does not write reports or modify supplied files.
Inputs and reports are held in browser memory, not local storage. Reloading
starts a new workspace.

To keep the synchronous numerical core responsive, the visual endpoint allows
one calculation at a time, up to 200 states, 500 transitions, a 1 MiB encoded
request, at most four changed parameters in sparse search, and at most 10,000
support evaluations. Individual file-picker inputs are limited to 450 kB.
The CLI remains available for larger investigations. Sparse search can still
be inconclusive within its budget; the interface displays that outcome.

## Verification

`make test-workspace` launches a separate Java server on an ephemeral loopback
port and checks the HTTP interface with Python's standard library. It checks
exact numerical report equality with the CLI, corruption/control examples,
locks, incompatible-lock reports, search budgets, provenance, Markdown/JSON
exports, invalid inputs, upload bounds, token checks, and filesystem isolation.
It additionally checks the 32-state/80-transition YiiP input if downloaded
locally. No datasets or build artifacts are committed.

The existing scientific regression suite remains `make test`. Browser checks
cover graph rendering, candidate selection, cycle explanations, lock changes,
conflict recovery, report downloads, and stale-input handling. There is no
claim that a rendered model or a numerical regression validates experimental
chemistry.

Verification on 2026-10-18: the HTTP/core suite passed 55 checks with local YiiP
data present; the original suite passed 47 author conditions / 35,696 core
assertions, 804 investigation assertions, and 56 CLI checks. In-browser checks
confirmed CSV file uploads, consistent/corrupted cases, the 32-state YiiP
graph, candidate/cycle selection, exact locks and conflict recovery, drag and
zoom controls, scientific notation, stale-report invalidation, and downloaded
JSON/Markdown contents. No frontend runtime packages were added.
