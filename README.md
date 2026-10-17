# mathhackshackathon

- `src/src.java`: Processing entry point, background map loading, camera, lighting, and rendering.
- `src/CityScene.java`: Cached Bloor–Yonge OSM buildings/parts, heights, streets, and meshes.
- `src/astar.java`: Six-direction A* through a 3D voxel grid.
- `src/stableFluids.java`: 3D staggered-grid Stable Fluids solver, viscosity, and pressure projection.
- `src/windSimulation.java`: Terrain/tree voxel boundaries and background fluid worker.
- `src/windVisualization.java`: Advected particle trails rendered as speed-coloured moving ribbons.
- `src/navigation.java`: Terrain/tree collision voxels and raycasting against terrain triangles.
- `src/map.java`: Terrain generation, map data, wind, tree sway, and leaf physics.
  Tree bending and leaf drag use the local simulated velocity.

Run `src.main()` in your Java IDE, or compile and run from the project root
on macOS/Linux with the jars in `lib`:

```sh
javac -cp "lib/*" -d bin src/*.java
java -cp "bin:lib/*" src
```

Click the terrain once to set the blue start marker, then again to set the green
goal. A completed route appears as a green line. Endpoints snap to free voxels
above the clicked terrain; tree trunks and canopies block the route, including
their wind sway envelope. A third click starts a new route. Picking and search run in a background worker
so the scene keeps animating; regenerating the map cancels pending work.

The app starts in **Bloor–Yonge** mode, using the offline OpenStreetMap cache.
Press **M** to switch between the city and the generated forest. Press **Space**
to pause/resume the orbiting camera. **R** chooses a new wind direction and clears
the route. In city mode it rebuilds the same cached neighbourhood; in forest mode
it also generates new terrain and trees. Map preparation runs off the drawing
thread. Clicks outside the terrain leave the current selection intact.

The Bloor–Yonge scene covers 460 × 460 metres centred on the actual mapped
intersection, with white building volumes, a flat ground plane and cached streets.
Building parts preserve towers and podiums. The HUD discloses inferred/default
heights and links to OSM attribution. See [data provenance and assumptions](data/README.md).
No network connection is needed to run it; `python3 scripts/cache-bloor-yonge.py`
explicitly refreshes the cache. City wind is prescribed at 12 m/s, not live weather.

Press **C** to show/hide flowing, speed-coloured ribbons, a ground wind-speed
heatmap, and a compact speed legend; the HUD text shows whether the overlay is on
or off. A small arrow at the top right shows wind travel direction in a fixed bird's-eye view (-Z is up, +X is right).
The corner arrow indicates the prescribed incoming wind. Ribbon heads are particles
advected with RK2 through the live 3D velocity field. Each ribbon retains about five
seconds of position history, so both its head and tail move with the flow rather
than displaying a stationary full-domain streamline. Short fragments and stalled
trails are hidden or recycled; new inlet particles appear only after building a
sufficiently long trail. Trails continue advancing while hidden. Blue/cyan represents slow
flow and yellow/red fast flow. The legend uses a fixed scale per map in scene units
per second, with its upper colour saturating at the labelled maximum. Leaves use
the same simulated velocity with drag and gravity. Hiding the overlay does not stop
physics. The ground heatmap samples wind 1.5 fluid cells above the terrain
(about 46 scene units in the forest or 10.8 m in the city). Sheltered/solid areas are blue;
faster flow progresses through green and yellow to red on exactly the same scale
as the ribbons and legend. Ground colours update with each fluid snapshot and
remain unlit so scene lighting cannot distort their numerical meaning.

The solver follows [Jos Stam's Stable Fluids](https://www.dgp.toronto.edu/public_user/stam/reality/Research/pdf/ns.pdf):
RK2 semi-Lagrangian velocity advection, implicit viscosity, and an incompressibility
projection using a preconditioned conjugate-gradient pressure solve. A staggered
MAC grid makes the pressure gradient and divergence compatible at solid faces.
Wind enters the upwind X/Z boundaries and exits the downwind sides. The upper
boundary is a slip boundary: no vertical flux, with zero tangential diffusion
gradient. This prevents a shallow open roof from draining the incoming wind before
it crosses the forest. Outer diffusion ghost values use zero gradients rather than
artificially dragging velocities toward zero. Terrain, trunks, and canopies occupy
impermeable voxels. Tree solids cover
their bounded sway envelopes rather than rebuilding moving boundaries every frame.

The forest grid is 64 x 17 x 64 (about 31 scene units per cell). The city grid is
64 x 61 x 64 (7.19 m per cell) with more vertical room for the mapped towers.
The city worker updates less often than rendering; its stable timestep is capped
at 0.2 s for the lower prescribed speed. It may run slower than wall time under load. Thin trunks and
canopies are conservatively voxelized, so their effective size is larger at this
resolution. This is a coarse graphics fluid simulation, not an engineering-validated
prediction of atmospheric turbulence. The solver runs on a background thread and
publishes immutable velocity snapshots; R disposes of the old worker and rebuilds
the fluid domain for the new map.

Run the pathfinding and numerical fluid checks:

```sh
javac -cp "bin:lib/*" -d bin tests/*.java
java -ea -cp "bin:lib/*" PathfindingTest
java -ea -cp "bin:lib/*" FluidTest
java -ea -cp "bin:lib/*" ParticleTrailTest
java -ea -cp "bin:lib/*" SustainedWindTest
java -ea -cp "bin:lib/*" CitySceneTest
```
