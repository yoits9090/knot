# Scientific Model Debugger

A Java tool for checking thermodynamic consistency in kinetic state graphs.
The project goal is to explain small conflicting reaction sets and compare
repairs against uncertainty and experimental observations.

**Implemented:** multibind's numerical foundation: uncertain free-energy
measurements -> consistent state energies, covariance and equilibrium
populations; uncertain forward/reverse rates -> consistent rate pairs.
There is a command-line interface. There is no graphical UI, automatic model
assumption inference, sparse repair search, or experimental-data fitting yet.

Based on Kenney & Beckstein (2023), [Thermodynamically consistent determination
of free energies and rates in kinetic cycle models](https://doi.org/10.1016/j.bpr.2023.100120).
See [the implementation and validation notes](docs/multibind-core.md) and
[the project brief for future sessions](AGENTS.md).

## Build and run

Requires JDK 21+, Make, and Python 3 for the initial checksum-verified download
of Commons Math 3.6.1. After that, the Java application runs without Python or
network access. Run commands from the repository root.

```sh
make run                          # CLI help
make data                         # fetch pinned sources; generate local test data and CLI examples
make run ARGS='energies examples/diamond/states.csv examples/diamond/graph.csv --ph 5'
make run ARGS='rates examples/transporter-rates.csv'
make run ARGS='energies examples/yiip-site-a/states.csv examples/yiip-site-a/graph.csv --ph 6.5 --concentration Zn=0.000001'
make run ARGS='energies examples/dtpa-central/states.csv examples/dtpa-central/graph.csv --ph 7'
make run ARGS='rates examples/transporter-diffusion-rates.csv'
make clean                        # remove compiled output; retain download caches
```

Scientific input tables and generated reference outputs are **not committed**.
`make data` requires Git, Python 3.11, 3.12, or 3.13, and network access for the
initial setup. It recreates the ignored `examples/` and
`src/test/resources/upstream/` directories, including local provenance with
source revisions and input hashes. Source URLs, original paths, and licensing
are documented in [third-party attribution](third_party/README.md). You can
also run the CLI directly on your own CSV files without preparing these data.

For ligand binding, add e.g. `--concentration Na+=0.15`; concentrations and
standard-state concentrations must use the same units (normally molar).
Free energies are dimensionless beta*G, conventionally called kT units.
Input variance columns contain **variances**, not standard deviations.

For VS Code or Devin, open `mathhackshackathon.code-workspace` to load the
Java source folders and Commons Math classpath. Run `make build` once after a
fresh clone to download the dependency. Folder settings are also included for
single-folder windows; the workspace file covers Java settings that require
workspace scope in multi-folder windows.

CSV output includes state energies, probabilities, measured/fitted differences,
residuals, and (for rate inputs) projected rates and the paper's approximate
rate errors. Rate matrices use `i,j = i -> j` and zero diagonals; they are not
continuous-time generator matrices.

## Verify

```sh
make data                         # one-time setup; also rerun to recreate local data
make test                         # local oracle fixtures + analytic/invalid-input tests; offline after setup
make reference                    # rerun authors' tests and generate fresh independent oracle outputs
```

`make test` compares 47 reference example conditions, covering free energies,
covariance, populations, macrostate energies/errors, and rate matrices/errors.
Fixtures have source commit IDs and input SHA-256 hashes. The nine-state rate
regression is ill-conditioned and has a separately documented tolerance plus
an independent closed-form ring check.

The expanded examples include a published YiiP zinc-transporter binding-site
model (32 states, 80 transitions), three DTPA fitted parameter sets, and four
additional transporter rate variants. There is also a negative test for an
upstream YiiP site-B table that omits eight states referenced by its graph.
These verify the numerical implementation and evaluate supplied fitted models;
they do not independently validate the experiments or repeat parameter fitting.

`make reference` needs Python 3.11, 3.12, or 3.13 and network access for its first
setup. It installs an isolated, pinned Python oracle under ignored
`build/reference/`, runs the authors' 30 tests, checks fresh outputs against
local fixtures created by `make data`, then compares Java against the fresh
outputs. The Java implementation never calls that oracle during normal use.

## Scientific scope

Supported inputs are connected graphs of well-defined molecular states with
reversible transitions and compatible equilibrium/standard-state measurements.
Declared concentrations can shift proton/ligand binding energies. Driven
nonequilibrium rates must be reduced to compatible intrinsic/standard-state
inputs before correction; the app cannot infer that transformation.

Consistency is not proof that measurements are correct, the model is complete,
or its chemistry is experimentally valid. Missing states/reactions cannot be
reconstructed by this method. The paper's Gaussian assumptions and uncertainty
approximations are exposed rather than treated as exact statistical guarantees.

## Previous project and attribution

The complete Toronto CFD/drone-pathfinding app and its runtime jars are
preserved on the pushed `old` branch. The new work remains on `main`.

Sources for the locally downloaded examples and generated regression data are
attributed in [third-party notices](third_party/README.md). The existing multibind Python
implementation is the independent reference, not a Java runtime dependency.
