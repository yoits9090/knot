# mathhackshackathon

- `src/src.java`: Processing entry point, camera, lighting, and rendering.
- `src/map.java`: Terrain generation, map data, wind, tree sway, and leaf physics.
  The wind field is currently a placeholder for a future CFD solver.

Run `src.main()` in your Java IDE, or compile and run from the project root
on macOS/Linux with the jars in `lib`:

```sh
javac -cp "lib/*" -d bin src/*.java
java -cp "bin:lib/*" src
```
