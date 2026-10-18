# Multibind numerical core

This implements the potential-graph maximum-likelihood estimator and Gaussian
rate projection in Kenney & Beckstein, *Biophysical Reports* 3 (2023), 100120,
DOI [10.1016/j.bpr.2023.100120](https://doi.org/10.1016/j.bpr.2023.100120).
The [open paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC10450860/),
[author library](https://github.com/Becksteinlab/multibind), and
[publication examples](https://github.com/Becksteinlab/multibind-publication-code)
are the scientific and verification sources.

## Implemented equations

All energies below are dimensionless beta*G. Each oriented edge i -> j provides
a measured difference d_e and positive measurement variance v_e.

1. **Gaussian energy MLE (Eq. 9).** Minimize
   `sum_e ((g_j - g_i) - d_e)^2 / v_e`. An incidence matrix D has -1 at
   each source and +1 at each destination. The Fisher matrix (Eq. 11) is
   `L = D^T W D`, where `W = diag(1/v_e)`.
2. **Solve and uncertainty (Eqs. 10-11).** Instead of forming normal equations,
   decompose `B = sqrt(W/max(W)) D` with SVD. The mean-zero energy estimate is
   `B^+ sqrt(W/max(W)) d`; covariance is
   `V diag(1/(s_k^2 max(W))) V^T` over the n-1 identifiable modes.
   This is algebraically `L^+`, while avoiding the squared condition number
   when solving for energies. Subtract g_0 from the reported energy vector.
3. **Equilibrium populations (Eqs. 6-7).** Normalize `exp(-g_i)` using a common
   energy shift to avoid exponential overflow. Macrostate energies are
   differences between negative log partition sums, also evaluated stably.
4. **Chemical inputs.** H+ edges interpret the value as pKa:
   `d = ln(10) (pH - pKa)`, `v = ln(10)^2 variance_pKa`.
   General binding uses `d = d_standard - ln(c/c_standard)`; direct `helm`
   edges already contain dimensionless energy and variance.
5. **Rates to energies.** For an equilibrium-compatible forward/reverse pair,
   `d = ln(k_reverse) - ln(k_forward)` and
   `v = variance_forward/k_forward^2 + variance_reverse/k_reverse^2`.
   Fit the resulting energy graph, then use its consistent differences.
6. **Rate projection (Eqs. 16, 18-20).** Set `K = exp(-d_fitted)` and
   `k_reverse_corrected = (v_f k_r + v_r K k_f)/(v_f + v_r K^2)`;
   `k_forward_corrected = K k_reverse_corrected`. The implementation uses log
   weights to avoid directly squaring large K. This minimizes the Gaussian
   rate adjustment objective for a fixed fitted energy difference.

Apache Commons Math 3.6.1 supplies SVD. Its Maven Central artifact is pinned by
SHA-256 in `scripts/fetch-dependencies.py`. No Python execution is involved in
the Java calculations.

## Uncertainty conventions and limits

The author implementation uses a mean-zero pseudoinverse covariance even
though it prints energies relative to the first state. We preserve that matrix
and its diagonal errors under explicitly named `centeredCovariance` and
`paperStateStandardError`. `referenceCovariance` transforms it to the
first-state reference gauge. An energy difference's covariance-aware variance
is `C_ii + C_jj - 2 C_ij`.

Macrostate differences expose two standard errors: `paperStandardError`
reproduces Appendix A.2 / the authors' diagonal-only propagation, while
`covarianceStandardError` includes the full covariance through the gradient
of the log partition sums. Neither is an exact confidence interval.

Rate standard errors reproduce the author's Appendix A.3 / Python formulas:
energy-difference error is approximated as `sqrt(C_ii + C_jj)`, propagated to
the reverse rate, then combined with the forward-rate energy factor as in the
reference. This is a compatibility output, not a complete treatment of all
correlations or measurement uncertainty. The Gaussian rate assumption is most
defensible close to consistency with small relative input errors, as discussed
in the paper. Large corrections require scientific scrutiny.

Zero rate variances use the authors' 1e-12 **standard-error** floor during
pair projection; they do not mean exact locked parameters. A pair with both
input variances zero has no usable finite weight for the energy fit and is
rejected. Direct energy edges require strictly positive variance. An explicit
exact-lock interface is future work.

Disconnected graphs, duplicate state pairs, invalid values, missing
concentrations, and missing reverse rates are rejected. The relative singular
value cutoff on B is sqrt(1e-15), matching a 1e-15 cutoff on L; graphs with
fewer than n-1 identifiable modes are rejected. The reported condition number
is that of L on its identifiable subspace. The CLI warns above 1e8.

The Java binding conversion honors `standard_state`. The upstream high-level
`build_cycle` currently omits this argument when calling its own binding helper;
all cross-language fixtures use standard state 1, so that difference does not
affect the comparisons. A separate analytic test covers non-unit standard state.

## Independent verification

Pinned reference commits:

- `Becksteinlab/multibind`: `7c93f605d99ff67d9adef890c9302bccd2caa1b5`
- `Becksteinlab/multibind-publication-code`:
  `4c9c28cba3b1a529db1abcaa775448136dad9135`
- `Becksteinlab/yiip_analysis`: `be13452486f2355673e8e393c85d5ef6e908823b`

Scientific inputs and generated outputs are excluded from Git. Run `make data`
to recreate the local fixtures and CLI examples from these pinned sources.
See [third-party attribution](../third_party/README.md) for original paths and
license notices.

The 47 locally generated cases in `src/test/resources/upstream/cases.csv` comprise:

| Source | Cases | Coverage |
|---|---:|---|
| Author four-state diamond | 4 pH values | Energies, covariance, populations, proton macrostates |
| Author sodium/proton graph | 3 pH x 3 Na+ concentrations | Proton and ligand conversion, bound/unbound macrostates |
| Author three-proton/two-binding graph | 3 pH values | Coupled six-state equilibrium example |
| Author canonical and distortion graphs | 2 | Direct energies and unequal variances |
| Publication DTPA fitted graph | 3 pH values | Evaluate the supplied fitted eight-state model |
| Publication DTPA central, terminal and simultaneous fits | 3 fits x 3 pH values | Different eight-state parameter sets from the publication's recorded fit runs |
| YiiP site A, separate eLife study | 3 pH x 3 zinc concentrations | 32 states, 80 transitions, 49 independent cycle constraints |
| Publication rates, transport rates, MD rates | 3 | Six-state energy fit and full projected rate/error matrices |
| Publication diffusion-rate variants | 4 | Added unbound-state transition, larger rates, absent transition, and scaled intrinsic rates |
| Author nine-state rate regression | 1 | Large dynamic range and zero individual rate variances |

The DTPA cases evaluate the authors' already fitted microscopic parameters.
They do not implement or validate the paper's inverse Monte Carlo fit to NMR.
The transporter cases project the supplied equilibrium-compatible rate tables;
they do not reproduce driven transport curves or the full paper's figures.

The additional YiiP data are the fitted site-A state/graph tables from Hussein
et al., [Energy coupling and stoichiometry of Zn2+/H+ antiport by the prokaryotic
cation diffusion facilitator YiiP](https://pmc.ncbi.nlm.nih.gov/articles/PMC10617992/),
DOI 10.7554/eLife.87167. Java is compared with multibind on the model at pH
5.6, 6.5 and 7.4, each at Zn concentrations 1e-12, 1e-6 and 0.01 M.
The source state table and fitted parameters are unchanged; only filenames are
normalized for the CLI. This evaluates an existing fitted model, not the
original MST inference, raw molecular dynamics or experimental validation.

The same repository's site-B `states.csv` lists only eight unbound states,
while `result.csv` also refers to S0Zn through S7Zn. The unmodified files are
retained as `rejected-yiip-site-b`. The Java importer must reject them. No missing
states are inferred or silently supplied, and the incomplete table is not
counted among the 47 successful reference conditions.

Expected outputs were generated by running the pinned Python implementation,
not by the Java solver. `provenance.json` records input hashes, commit IDs, and
the Python environment; requirements are pinned in
`scripts/reference-requirements.txt`.

`make test` checks every stored energy, probability, covariance entry,
macrostate energy/error, and rate/error entry using absolute tolerance 2e-10
and relative tolerance 2e-8. The nine-state example has Fisher condition number
about 2.49e9. Its reference comparison uses 1e-6 absolute / 1e-5 relative
tolerance because the reference's normal-equation pseudoinverse loses accuracy.
Java is also checked against an independent closed-form single-ring projection
at 1e-9 absolute / 1e-10 relative tolerance. Seven author rate-pair regression
examples are checked at their published 1e-5 relative tolerance.

Additional checks cover analytic energy solutions, weighting, probability
normalization, covariance symmetry, gauge transformations, detailed balance,
rate-projection optimality at fixed energy, extreme energies, CSV parsing, and
invalid inputs. After `make data`, `make test` runs offline.
`make reference` reruns the author's 30 tests and compares against fresh oracle
outputs while checking local input hashes and baseline drift. `make data`
(`python3 scripts/reference-check.py --regenerate`) explicitly recreates the
ignored local fixtures and examples; ordinary verification never does so.
The baseline is generated locally from pinned author code, not stored in Git.

### Recorded verification, 2026-10-17

`make reference` passed for the initial 25-case suite. The expanded suite's
verification runs the authors' 30 Python tests, fresh regeneration and
baseline checks for all 47 cases, and 35,696 Java assertions (including the
incomplete-table rejection). Maximum observed
`abs(Java - reference) / max(1, abs(reference))` was 8.69e-11 across the 24
initial well-conditioned cases and remains 8.69e-11 across all 46 expanded
well-conditioned cases; it is 5.62e-7 including the ill-conditioned nine-state
case. These are numerical cross-implementation checks, not experimental
validation of the underlying chemistry. Both documented example CLI commands
also completed successfully; additional YiiP, DTPA and diffusion examples are
available under `examples/`.

## Boundaries

This is a dense numerical implementation for small/moderate molecular state
graphs. It does not yet implement SBML import, generalized reaction-network
stoichiometry, atom/charge checks, missing-state inference, exact parameter
locks, sparse minimal repairs, minimal conflict localization, time integration,
driven transport, inverse Monte Carlo, or a UI. It cannot establish that a
consistent model is experimentally correct. Users must supply compatible
conditions and complete, appropriate state definitions.
