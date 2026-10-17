# Toronto wind and 3D A*

A Processing/Java 21 app with a citywide Toronto atlas, a local 3D Stable Fluids
simulation driven by real Environment and Climate Change Canada observations,
and visible incremental 3D A* searches.

Run from the project root with the jars in `lib`:

```sh
javac -cp 'lib/*' -d bin src/*.java
java -Xmx2g -cp 'bin:lib/*' src
```

## Controls

| Key/input | Action |
|---|---|
| W / S | Move forward / backward across the ground plane |
| A / D | Move sideways |
| Q / E | Move down / up |
| Left / Right arrows | Turn the horizontal camera angle |
| Up / Down arrows | Tilt the camera |
| Right mouse drag | Look around |
| Mouse wheel | Change camera height |
| Shift + movement | Move faster |
| O | See all of Toronto from above |
| P | New bird's-eye view of a route between random real intersections at random altitudes |
| Left click twice | Select a roof, wall or ground surface for start/goal |
| C | Toggle moving wind ribbons, ground-speed heatmap and speed legend |
| R | Previous cached observed wind hour in Toronto; new map/wind in forest mode |
| L | Fetch the latest available ECCC observation |
| B | Show/hide the historical wind backtest results |
| M | Switch between Toronto and the procedural forest |

A* expands **one actual cell every 0.05 seconds**, with no fast-forward or fake
search geometry. Blue points are explored cells, gold points are the discovered
frontier, pink points are blocked neighbours, and a yellow line shows the current
candidate. The completed route is green. P replaces the search and fits the
camera above its endpoints. Routes use six neighbours in a 10 m 3D grid, with
random endpoint altitudes from 30–170 m above the flat ground. Building checks use a 1 m clearance envelope around points
and every connecting segment; an entire 10 m cell is not treated as an obstacle
when a building touches its edge. This keeps downtown intersections usable while
preventing paths from crossing thin walls between samples. The displayed
P start/goal coordinates are the real intersections; cell-centre quantization is
used between them. Manual clicks raycast the nearest building roof/wall or ground
using the actual extruded footprints, including courtyard holes. They retain the
clicked 3D position, offset 3 m along the surface normal into free space, instead
of snapping to an intersection or discarding the picked altitude. Building picking
runs in the background through the atlas spatial buckets. Closely spaced points
in one grid cell retain separate exact endpoints. A search cap of 250,000 discovered cells bounds memory.
P chooses origins across the city, with the second intersection 0.5–1.8 km away
so playback normally finishes in seconds rather than minutes. New routes can be
requested during a search. Picking/loading run off the drawing thread; incremental
search work is bounded to one expansion per rendered update. At low frame rates,
playback slows down rather than skipping visible steps.

## Toronto coverage and rendering

The cached **2025 City of Toronto building massing** contains 428,184 volumes.
The official centreline cache contains 40,580 selected surface road segments
and 18,133 intersections. The atlas spans the municipality, including Steeles,
Etobicoke, Scarborough and the harbour/islands. It uses local east/south metres
centred on Bloor/Yonge. All footprints and roads appear in the overview texture;
nearby 500 m tiles show footprint extrusions, with batched meshes and a bounded
cache. The city overview adds tall-building proxies. This is level-of-detail
rendering, not every building at full mesh detail simultaneously. Near tiles load
progressively. The ground is flat and building volumes use the City's `AVG_HEIGHT`
field; roof form and terrain elevations are not reconstructed. The official major
shoreline supplies the lake/island overview, joining endpoint gaps under 200 m.

The **640 × 640 m CFD window** follows the ground position viewed by the camera,
including P routes. It is rebuilt in the background when moving far enough, at
most once per three seconds. Only this local window has a resolved wind field;
the rest of the city is geometry. The 64-cell fluid width gives 10 m cells, and
the ground heatmap samples **15 m above ground**, not pedestrian height. The
fluid roof lies at least 355 m above ground or 115 m above the tallest local
building. Loading a new CFD window preserves a city route/search.

## Real wind and backtesting

Toronto starts from the cached latest valid hourly observation from **ECCC
Toronto City Centre / Billy Bishop, climate ID 6158667**. The app requests the
latest available observation on startup and every 15 minutes; L refreshes it
immediately. A timeout or missing service leaves the dated cached value in place.
The HUD always shows the observation UTC time and labels delayed observations.
R enters historical mode and disables automatic latest refresh until L is used.
These are hourly station observations, not a live street weather sensor network.

ECCC wind speed is converted from km/h to m/s. Meteorological direction is
reported in tens of degrees **from** north; it is converted to the direction the
wind travels: `east = -speed*sin(from)`, `south = speed*cos(from)`. That vector
drives the solver's inflow boundaries. It is not a randomized arrow field.
Station measurements are imposed uniformly as the local boundary condition;
no measured vertical profile is available.

Press B to inspect the reproducible historical input backtest. The included
cache has 2,040 valid hourly records, July 20–October 17, 2026. Only consecutive
valid one-hour pairs enter evaluation. The first 70% of pairs fit a vector AR(1)
model; the last 588 pairs are held out chronologically, with no shuffling. A
one-hour persistence baseline is also evaluated. Speed MAE/RMSE, vector RMSE and
circular direction MAE are reported; directions below 1 m/s are excluded.
Persistence speed MAE is 1.093 m/s and vector RMSE 2.026 m/s. AR(1) speed MAE is
1.160 m/s and vector RMSE 1.996 m/s: it does not improve speed MAE. The app uses
actual observations for replay, not these baseline forecasts.

**This backtests the station wind input, not street-level CFD accuracy.** Local
flow, turbulence and altitude winds remain unvalidated without independent
anemometer measurements. Flat terrain, 10 m fluid cells, estimated massing and
simple inflow/roof conditions limit interpretation. This is a graphics fluid
simulation, not an engineering wind-safety assessment.

Refresh data explicitly (the app runs from caches offline):

```sh
python3 scripts/cache-weather.py              # download observations and rerun backtest
python3 scripts/cache-weather.py --offline    # reproduce from the included raw response
# Geometry preparation dependencies are needed only when rebuilding the atlas:
python3 -m venv /tmp/toronto-data-env
/tmp/toronto-data-env/bin/pip install pyshp shapely pillow
/tmp/toronto-data-env/bin/python scripts/cache-toronto.py
```

See [data/README.md](data/README.md) for provenance, source hashes and licences.

## Simulation and checks

`stableFluids.java` implements RK2 semi-Lagrangian advection, implicit diffusion
and a PCG incompressibility projection on a staggered MAC grid, following
[Stam's Stable Fluids](https://www.dgp.toronto.edu/public_user/stam/reality/Research/pdf/ns.pdf).
Terrain/buildings/trees occupy conservative solid voxels. The roof has zero normal
flow; horizontal upwind boundaries supply the measured vector and downwind sides
are open. A background worker publishes immutable snapshots. Simulation may lag
wall time under load. Ribbon particles use the actual sampled 3D field and retain
about 19 seconds of history in the city (five seconds in the forest); the finite
tails move and expire. Short/stalled fragments are filtered. Ground and ribbons
use the same blue-to-red speed scale. Forest leaves and tree bending also sample
the simulated velocity. The small Bloor/Yonge OSM loader remains covered by tests.

```sh
javac -cp 'bin:lib/*' -d /tmp/mathhack-checks tests/*.java
for test in BuildingPickingTest TorontoTest PathfindingTest CitySceneTest FluidTest ParticleTrailTest SustainedWindTest; do
  java -ea -Xmx2g -cp '/tmp/mathhack-checks:bin:lib/*' "$test"
done
```
