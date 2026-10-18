# Scientific and third-party attribution

Scientific data is downloaded or generated locally and excluded from Git.
Run `make data` to recreate `examples/` and `src/test/resources/upstream/`
from the pinned repositories below. Source checkouts and the Python oracle
are cached under ignored `build/reference/`. The setup script
[`scripts/reference-check.py`](../scripts/reference-check.py) specifies all
input paths and test conditions; its local `provenance.json` records source
commits, input SHA-256 hashes, and the Python environment. Only code,
documentation, and license notices are intended for version control.

## Multibind

Kenney, Ian M. and Oliver Beckstein (2023). *Thermodynamically consistent
determination of free energies and rates in kinetic cycle models*.
Biophysical Reports 3, 100120. DOI: 10.1016/j.bpr.2023.100120.

The Java implementation follows the paper and adapts the numerical conventions
and rate-error formulas of https://github.com/Becksteinlab/multibind at commit
`7c93f605d99ff67d9adef890c9302bccd2caa1b5`. Its example CSVs and seven
rate-pair regression cases are used with attribution. The upstream software is
MIT-licensed; the original copyright and permission notice are retained in
`multibind-LICENSE.txt`.

The small numerical publication input tables are from
https://github.com/Becksteinlab/multibind-publication-code at commit
`4c9c28cba3b1a529db1abcaa775448136dad9135`; that repository does not state a
separate software license. The tables are downloaded as attributed scientific
numerical test inputs, not relicensed or committed here. No publication
application code or notebooks are redistributed. Local fixture provenance
identifies original paths and hashes.
`examples/transporter-rates.csv` is the publication's
`thermodynamic_rates/inputs/rates.csv`; `examples/diamond/` is the library's
`examples/input/4-state-diamond/`.

The `examples/dtpa-{central,terminal,simultaneous}/` directories are local copies
of the publication repository's `states.csv` and `best_graph.csv` (renamed
`graph.csv`) under `nmr_data_fitting/runs/<run>/inputs/`:

| Local example | Upstream run |
|---|---|
| `dtpa-central` | `9dfbfebf-46c2-4b0f-9f61-88a9d3005b9a-center` |
| `dtpa-terminal` | `0f70019b-8a3d-4ada-a6be-81f80564af7f-side` |
| `dtpa-simultaneous` | `c13e70b4-ee59-45f8-b113-ab0ed37bbeb3-None` |

`examples/transporter-diffusion-rates.csv` is a local copy of
`full_antiporter_example/inputs/diffusion_rates.csv`. Exact source hashes are
recorded in the fixture provenance.

## YiiP experimental-study model

Hussein et al., *Energy coupling and stoichiometry of Zn2+/H+ antiport by the
prokaryotic cation diffusion facilitator YiiP*, eLife (2023), DOI
10.7554/eLife.87167. Source: https://github.com/Becksteinlab/yiip_analysis,
commit `be13452486f2355673e8e393c85d5ef6e908823b`.

The upstream repository is GPL-3.0; its license is retained in
`yiip-LICENSE.txt`. The numerical state/parameter tables from
`MST_inference/siteA/{state,result}.csv` and the incomplete site-B tables are
downloaded locally with this attribution and upstream license. No Python
application code or notebooks from this repository are incorporated into the
Java implementation.
`examples/yiip-site-a/` contains the unchanged site-A tables, with filenames
normalized to `states.csv` and `graph.csv`. Corresponding source paths and
SHA-256 hashes are in `src/test/resources/upstream/provenance.json`.

## Apache Commons Math

Commons Math 3.6.1 provides the SVD implementation. The application fetches the
unaltered JAR from Maven Central and verifies its SHA-256. Its license and
notice, extracted from that artifact, are retained in
`commons-math3-LICENSE.txt` and `commons-math3-NOTICE.txt`.
