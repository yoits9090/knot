# Debugger workflow goal

Accepted 2026-10-18: turn the verified multibind core into an inspectable CLI
investigation before building UI.

- [x] Find signed cycle witnesses with propagated measurement uncertainty.
- [x] Distinguish numerical closure discrepancies from statistical tension;
      report a global Gaussian lack-of-fit test and multiplicity-adjusted cycle
      tests without identifying a particular measurement as wrong.
- [x] Validate consistent controls and known, documented parameter corruptions.
- [x] Add exact locks on supplied energy/pKa/binding parameters using equality
      constraints, including explanations for incompatible locks.
- [x] Compare weighted least-squares repair with bounded sparse alternatives;
      expose search bounds, completion, and the precise minimality claim.
- [x] Provide a CLI investigation with readable Markdown and structured JSON
      export, including assumptions, input provenance, evidence, and repairs.
- [x] Run the author-reference suite plus new scientific/CLI tests, document
      actual results, and update the project brief.

Scope: molecular state-graph energy differences at compatible equilibrium or
standard-state conditions. Locks preserve input parameter values after the
declared concentration conversion. Individual directed-rate locks, generalized
reaction stoichiometry, trajectory/experimental-data fitting, and UI are outside
this goal. Measurement agreement means uncertainty-weighted agreement with the
supplied edge measurements, not experimental validation of the chemistry.

Completed 2026-10-18. `make reference` passed the authors' 30 Python tests,
47 author example conditions / 35,696 core assertions, 804 investigation
scientific assertions, 56 end-to-end CLI/report checks, and 492 independent
SciPy null-space/statistical checks over 12 seeded networks. The scientific
tests include 30 additional seeded networks, consistent controls, known
corruptions, exact-lock conflicts, and bounded-search semantics. Java compiles
with `-Xlint:all` without warnings. See `docs/investigation.md` for methods,
scope, command-line options and interpretation.
