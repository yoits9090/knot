# Scientific Model Debugger

A Java tool for finding thermodynamic inconsistencies in kinetic state graphs,
explaining conflicting cycles, and comparing repairs against measurement uncertainty.
Includes a local visual workspace and a command-line interface.

## What works

- Consistent state energies, covariance, equilibrium populations, and rate pairs
- Signed cycle witnesses with uncertainty-aware diagnostics
- Exact energy/pKa locks and bounded sparse repair alternatives
- Interactive network graph, cycle inspection, and repair comparisons
- Markdown and JSON reports with assumptions and input hashes
- Numerical regression checks against multibind's published examples

## Quick start

Requires JDK 21+, Make, and Python 3 for the initial dependency download.

```sh
make workspace
```

Open [127.0.0.1:8080](http://127.0.0.1:8080), load the synthetic example,
confirm the model assumptions, and select **Analyze model**. Inspect a cycle,
lock trusted parameters, and compare candidate repairs. Calculations run locally
through the Java solver. Use `make workspace PORT=8081` to change the port.

For the CLI:

```sh
make run ARGS='investigate samples/investigation/states.csv samples/investigation/corrupted.csv --assume-equilibrium'
```

Add `--lock 1,2` to preserve input edges or `--format json` for JSON output.

## How it works

Measurements define energy differences between molecular states. The solver
estimates a consistent set of state energies using uncertainty-weighted least
squares, then exposes cycles whose signed differences fail to close. Constrained
repairs preserve locked measurements; a bounded sparse search offers alternatives
that change fewer parameters.

The numerical core follows Kenney & Beckstein (2023),
[Thermodynamically consistent determination of free energies and rates in kinetic cycle models](https://doi.org/10.1016/j.bpr.2023.100120).
It uses Commons Math 3.6.1 for SVD and plain JavaScript/HTML/SVG for the workspace.

## Scope

Supports reversible molecular-state graphs with compatible equilibrium or
standard-state inputs. Users must confirm these assumptions. Repair costs measure
adjustment relative to input uncertainty; independent experimental-data fitting
and general SBML import are not implemented. Sparse search is bounded, and a
consistent repair is a candidate rather than proof of correct chemistry.

## Verify

```sh
make test-workspace  # synthetic HTTP/core checks; no scientific-data setup needed
make data            # download pinned sources and generate local reference fixtures
make test            # analytic, regression, investigation, and CLI tests
make reference       # fresh comparisons with the authors' implementation
```

`make data` requires Git, Python 3.11–3.13, and network access. Downloaded data
and generated fixtures stay local and ignored.

## Documentation

- [Visual workspace](docs/workspace.md)
- [CLI investigations and input formats](docs/investigation.md)
- [Equations and numerical validation](docs/multibind-core.md)
- [Data sources and attribution](third_party/README.md)

The previous Toronto CFD/drone-pathfinding application is preserved on [`old`](https://github.com/yoits9090/mathhackshackathon/tree/old).
