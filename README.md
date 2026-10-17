# mathhackshackathon

- `src/src.java`: Processing entry point, camera, lighting, and rendering.
- `src/astar.java`: Six-direction A* through a 3D voxel grid.
- `src/windVisualization.java`: Toggleable wind arrows and animated tracers from the current wind model.
- `src/navigation.java`: Terrain/tree collision voxels and raycasting against terrain triangles.
- `src/map.java`: Terrain generation, map data, wind, tree sway, and leaf physics.
  The wind field is currently a placeholder for a future CFD solver.

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
so the scene keeps animating; reset cancels pending work.

Press **Space** to pause/resume the orbiting camera and **R** to clear the route.
Clicks outside the terrain leave the current selection intact.

Press **C** to show/hide cyan wind arrows and moving streaks; the compact HUD
text shows whether the overlay is on or off. A small arrow at the top right shows
wind travel direction in a fixed bird's-eye view (-Z is up, +X is right).
It follows the same wind angle as the trees and leaves. Tracer travel is scaled for visibility;
this visualizes the existing uniform wind model, not a CFD solver.

Run the pathfinding and terrain-picking checks:

```sh
javac -cp "bin:lib/*" -d bin tests/PathfindingTest.java
java -ea -cp "bin:lib/*" PathfindingTest
```
