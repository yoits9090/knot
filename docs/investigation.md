# CLI investigation workflow

This extends the multibind numerical foundation into an inspectable
investigation of molecular-state energy graphs. It supports compatible
equilibrium/standard-state measurements, fixed declared pH/concentrations,
independent Gaussian errors with supplied variances, and a connected graph.
An operator must explicitly assert the supported conditions; the app cannot
infer those assumptions from a CSV.

## Run and export

```sh
make run ARGS='investigate samples/investigation/states.csv samples/investigation/corrupted.csv --assume-equilibrium'
make run ARGS='investigate samples/investigation/states.csv samples/investigation/corrupted.csv --assume-equilibrium --lock 1,2'
make run ARGS='investigate samples/investigation/states.csv samples/investigation/corrupted.csv --assume-equilibrium --format markdown --output build/investigation.md'
make run ARGS='investigate samples/investigation/states.csv samples/investigation/corrupted.csv --assume-equilibrium --format json --output build/investigation.json'
```

The synthetic sample is self-authored and versioned in `samples/`; downloaded
scientific tables remain local under `examples/` and the ignored test fixtures.
After `make data`, a published model can be inspected with:

```sh
make run ARGS='investigate examples/yiip-site-a/states.csv examples/yiip-site-a/graph.csv --assume-equilibrium --ph 6.5 --concentration Zn=0.000001 --max-changes 0 --format json --output build/yiip-investigation.json'
```

Options:

| Option | Meaning |
|---|---|
| `--assume-equilibrium` | Assert compatible equilibrium/standard-state molecular-state inputs |
| `--ph VALUE` | Fixed pH, default 0 as in the energy CLI |
| `--concentration NAME=VALUE` | Fixed ligand concentration; repeat for different ligands |
| `--lock 1,2` | Preserve those input parameters; one-based edge order, excluding comments/header |
| `--alpha VALUE` | Significance threshold in (0,1), default 0.01 |
| `--max-changes N` | Largest mutable support size explored, default 2; clipped to number of edges |
| `--search-budget N` | Support evaluations allowed, default 2000, maximum 100000 |
| `--alternatives N` | Best candidates retained at the minimum found size, default 3, maximum 20 |
| `--format markdown\|json` | Readable report or structured export; default Markdown |
| `--output PATH` | Write the report; no stdout report when specified |

Exit codes: 0 means an investigation was produced, including statistically
flagged inputs or an inconclusive support search; 2 means invalid input,
unsupported/missing assumptions, numerical failure or an I/O error; 3 means
the user's exact locks conflict and the report contains a witness. A report
cannot overwrite either input, including through a symlink. SHA-256 input
hashes are checked before and after analysis to detect concurrent changes.

## Signed witnesses and statistical interpretation

For each connected state graph, breadth-first spanning-tree edges define a
fundamental cycle basis. Every non-tree edge closes a simple cycle with its
tree path. Each step records its original edge number, orientation, endpoints,
and signed measured energy difference. A connected n-state, m-edge graph has
m-n+1 such independent constraints. These witnesses are not necessarily the
shortest cycles, and cycle selection depends on the spanning tree/input order.

A witness's closure is `r = sum_e sign_e d_e`. Assuming independent edge
measurements, its standard error is `sqrt(sum_e v_e)` and its standardized
discrepancy is `z = r / standard_error`. Two-sided Gaussian p-values are
Bonferroni-adjusted over the entire computed basis, including witnesses omitted
from the Markdown display. JSON retains all of them. Shared-edge cycle tests
are correlated; no independence of cycle tests is assumed by that adjustment.

The global statistic is the unconstrained MLE's
`chi_square = min_g sum_e ((g_j-g_i)-d_e)^2/v_e`. Under the stated Gaussian
model with known independent variances its residual degrees of freedom are
m-n+1. The global p-value uses the chi-square survival function. This global
test does not depend on which cycle basis is displayed. Trees have no cycle
test and `global_p = null`, not evidence that their chemistry is correct.

Reports separately label a numerical nonclosure and statistical tension. A
small nonzero sum is expected from uncertain measurements and need not be
flagged statistically. Global significance does not identify a faulty edge.
P-values depend on the asserted noise model and can underflow to zero for
extreme discrepancies. Very ill-conditioned graphs receive a sensitivity note.

Numerical feasibility uses an absolute-plus-relative tolerance
`1e-10 + 1e-12 * local absolute energy scale`. This defines an approximate
floating-point algebraic constraint, not a significance threshold.

## Exact locks and conditional repair

For fixed pH and concentrations, locking an input pKa, direct energy, or
standard binding energy fixes its converted difference `g_j-g_i = d_e`.
It does not set the supplied measurement variance to zero. Individual
directed kinetic-rate locks are outside this interface.

The solver traverses the locked-edge graph, assigns relative offsets within
each fixed component, and detects incompatible closed locked paths. An
infeasible lock policy returns a signed locked-cycle certificate; no repair
is issued. This is an algebraic trust-policy contradiction even if the
measurement discrepancy would be statistically small.

Otherwise, write `g_i = offset_i + x_component(i)`. A square-root weighted
incidence SVD estimates the remaining component potentials by minimizing the
same uncertainty-weighted measurement adjustment objective. This solves the
equality-constrained least-squares problem without approximating locks by
huge weights. All lock residuals are rechecked. Component covariance is
propagated to edge-difference uncertainties; fixed differences have zero
conditional uncertainty. This uncertainty conditions on the trust policy and
chosen support, and excludes uncertainty from selecting that policy/support.

Candidate changes are converted back to the original parameter units. At a
fixed pH, a delta in converted protonation energy corresponds to
`pKa_candidate = pKa_original - delta_energy/ln(10)`. Direct/standard binding
energies change by delta_energy. Locked original parameters are retained.

## Sparse alternatives and minimality

The weighted repair can distribute adjustments over many measurements. To
show alternatives changing fewer parameters, enumerate mutable edge supports
in order of increasing size. Every other edge is fixed to its supplied value;
user locks are always fixed. Feasible supports are solved by the same exact
constraint method, ranked by measurement adjustment cost, and a bounded number
of candidates are retained.

- If all smaller support sizes have been exhausted and a feasible size-k
  support actually changes k parameters, the minimum change count is certified
  within numerical tolerance and the user's lock policy.
- Full ranking at that size is certified only if all supports of that size
  were evaluated. A budget can interrupt ranking after minimum size is known.
- Exhausting the budget before finding a repair proves neither infeasibility
  nor minimum size. Completely searching through a configured change limit
  without a repair only rules out repairs within that limit.

These claims are about algebraic agreement with the supplied means. They do
not establish the true chemistry, which measurement is faulty, or whether
ordinary measurement noise should be corrected. Repairs are optional candidate
projections. The displayed chi-square cost measures adjustment relative to
the input edge uncertainties, not fit to independent experimental trajectories.
No changes are automatically written back to a model.

## Controlled corruption and reproducibility

`samples/investigation/consistent.csv` has g_A=0, g_B=1, g_C=2 and edge
differences (+1,+1,-2), each with variance 0.0004. The corrupted sample changes
only the final edge to -1. The app is not given the identity of that injection.
Its signed nonclosure is 1 kT, about 28.87 standard errors. Three different
one-edge corrections are possible without a trust policy. Locking the first
two selects the injected edge for correction; locking all three is infeasible.

`InvestigationTest` checks analytic cycle sums, noise controls, conditional
uncertainty, overlapping/disjoint cycles, pKa conversion, exact locks,
incompatible locks, budget/minimality semantics, and 30 seeded networks.
The CLI tests check JSON round trips, Markdown escaping, source hashes,
output handling, exit codes, input preservation, ranked alternatives and the
published 32-state YiiP graph. `make reference` additionally runs an independent
SciPy oracle on 12 seeded graphs: it anchors state 0 and solves in the null
space of the equality matrix, a different formulation from Java's fixed-edge
component contraction. It compares energies, objectives, conditional errors,
and global/cycle p-values.

Scientific foundations and related prior work:

- [Kenney & Beckstein (2023)](https://doi.org/10.1016/j.bpr.2023.100120): potential-graph MLE and thermodynamic cycle constraints.
- [Best Practices for Alchemical Free Energy Calculations](https://pmc.ncbi.nlm.nih.gov/articles/PMC8388617/): interpretation of noisy cycle nonclosure.
- [A variational method for network-wide analysis of relative ligand binding free energies with loop closure and experimental constraints](https://pmc.ncbi.nlm.nih.gov/articles/PMC8011336/): related constrained network estimation.

The debugger workflow builds on established mathematics. Its usefulness and
originality require assessment beyond successful numerical reproduction.

## Verification receipt, 2026-10-18

`make reference` passed:

- 30 tests in the pinned authors' Python implementation.
- 47 reference conditions and 35,696 original numerical-core assertions.
- 804 new Java investigation assertions, including 30 seeded networks.
- 56 end-to-end CLI/report checks.
- 492 independent SciPy comparisons on 12 seeded networks.

The original reference errors remain unchanged: maximum scaled error
`abs(Java-reference)/max(1,abs(reference))` is 8.69e-11 over well-conditioned
cases and 5.62e-7 including the sensitive nine-state example. These are
implementation and controlled-corruption checks, not independent experimental
validation. No UI or automatic model-file repair was introduced.
