# Debugger workflow goal

Accepted 2026-10-18: turn the verified multibind core into an inspectable CLI
investigation before building UI.

- [ ] Find signed cycle witnesses with propagated measurement uncertainty.
- [ ] Distinguish numerical closure discrepancies from statistical tension;
      report a global Gaussian lack-of-fit test and multiplicity-adjusted cycle
      tests without identifying a particular measurement as wrong.
- [ ] Validate consistent controls and known, documented parameter corruptions.
- [ ] Add exact locks on supplied energy/pKa/binding parameters using equality
      constraints, including explanations for incompatible locks.
- [ ] Compare weighted least-squares repair with bounded sparse alternatives;
      expose search bounds, completion, and the precise minimality claim.
- [ ] Provide a CLI investigation with readable Markdown and structured JSON
      export, including assumptions, input provenance, evidence, and repairs.
- [ ] Run the author-reference suite plus new scientific/CLI tests, document
      actual results, and update the project brief.

Scope: molecular state-graph energy differences at compatible equilibrium or
standard-state conditions. Locks preserve input parameter values after the
declared concentration conversion. Individual directed-rate locks, generalized
reaction stoichiometry, trajectory/experimental-data fitting, and UI are outside
this goal. Measurement agreement means uncertainty-weighted agreement with the
supplied edge measurements, not experimental validation of the chemistry.
