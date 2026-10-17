# Scientific Model Debugger

A MathHacks project for chemists and scientific modellers: find where a reaction
model violates physical constraints, explain the smallest conflicting set, and
compare possible repairs while preserving agreement with experimental data.

**Status:** Java starter only. The scientific analysis and user interface have
not been implemented. See [AGENTS.md](AGENTS.md) for the project direction and
scientific boundaries shared by future development sessions.

## Intended workflow

1. Import a supported chemical reaction model and, when available, measurements
   with parameter uncertainties.
2. Check the applicable conservation and thermodynamic constraints.
3. Highlight a small conflicting reaction set with an inspectable mathematical
   explanation.
4. Compare repairs that respect trusted parameters and reported uncertainties.
5. Show how each repair changes the model's agreement with observations.

The initial scope is closed, reversible, mass-action reaction networks under
conditions where detailed balance applies. Open and externally driven systems
require explicit boundary inputs and a different analysis.

## Run the starter

Requires JDK 21 or newer and Make. There are no third-party dependencies.

```sh
make run
make clean
```

Without Make:

```sh
mkdir -p build/classes
javac --release 21 -d build/classes src/main/java/org/mathhacks/Main.java
java -cp build/classes org.mathhacks.Main
```

## Previous project

The Toronto terrain, CFD, and drone-pathfinding project, including its Java
runtime dependency jars, is preserved on the remote `old` branch. Git history
is retained. Switch with `git switch old` after saving any current changes.

## Research foundations

- [Thermodynamically consistent determination of free energies and rates in
  kinetic cycle models](https://pmc.ncbi.nlm.nih.gov/articles/PMC10450860/)
- [Thermodynamically feasible kinetic models of reaction
  networks](https://pmc.ncbi.nlm.nih.gov/articles/PMC1861785/)
- [MEMOTE consistency checks](https://memote.readthedocs.io/en/latest/autoapi/test_consistency/index.html)

Existing tools already perform consistency checks and parameter correction.
Our proposed contribution is an integrated explanation-and-repair workflow;
its originality and usefulness must be established against those tools.
