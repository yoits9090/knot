import processing.core.*;
import java.util.*;

public class CitySceneTest {
    public static void main(String[] args) {
        CityScene city = CityScene.load();
        assert city.buildings.size() > 100;
        assert city.taggedHeights > 100 && city.levelHeights > 50 && city.defaultHeights > 0;
        assert city.tallest > 300 && city.tallest < 350 : "Tall tagged tower heights must not be flattened";
        float[] origin = city.project(city.latitude, city.longitude);
        assert origin[0] == 0 && origin[1] == 0;
        float[] northeast = city.project(city.latitude + 0.001, city.longitude + 0.001);
        assert northeast[0] > 0 && northeast[1] < 0 : "North must map to negative Z";
        assert city.roads.stream().anyMatch(r -> r.name.equals("Yonge Street"));
        assert city.roads.stream().anyMatch(r -> r.name.startsWith("Bloor Street"));
        assert Math.abs(CityScene.length("100 ft", 0) - 30.48) < 0.001;
        assert CityScene.length("unknown", 12) == 12;
        for (CityScene.Building building : city.buildings) {
            assert building.height > building.bottom && building.height > 0;
            assert building.bounds.getMinX() >= -CityScene.SPAN / 2 - 0.001;
            assert building.bounds.getMaxX() <= CityScene.SPAN / 2 + 0.001;
            assert building.bounds.getMinY() >= -CityScene.SPAN / 2 - 0.001;
            assert building.bounds.getMaxY() <= CityScene.SPAN / 2 + 0.001;
        }
        src sketch = new src(); sketch.width = 800; sketch.height = 600;
        try {
            long begin = System.nanoTime();
            sketch.regenerateMap();
            assert sketch.pendingMap != null : "City loading must run off the drawing thread";
            PathfindingTest.awaitMap(sketch);
            map world = sketch.world;
            assert world.city != null && world.trees.length == 0;
            assert world.terrainHeight == 0 && world.cols == 93;
            stableFluids solver = world.wind.solver;
            world.wind.close(); world.wind.awaitStopped();
            assert solver.cell < 8 && solver.ny * solver.cell > city.tallest + 100;
            assert Math.abs(Math.hypot(solver.inletX, solver.inletZ) - 12) < 0.001;
            assert !solver.latest.isSolid(0, -10, 0) : "Bloor-Yonge intersection must remain open";
            int[] start = sketch.navigationGrid.endpoint(new PVector(0, 0, 0));
            assert start != null && !sketch.navigationGrid.blocked[start[0]][start[1]][start[2]];
            int marked = 0;
            for (CityScene.Building building : world.city.buildings) {
                double x = building.bounds.getCenterX(), z = building.bounds.getCenterY();
                if (!building.footprint.contains(x, z)) continue;
                float y = -(building.height + building.bottom) / 2;
                assert solver.latest.isSolid((float) x, y, (float) z) : "Fluid must collide with OSM building " + building.id;
                int gx = (int) ((x - sketch.navigationGrid.minX) / sketch.navigationGrid.cell);
                int gy = (int) ((y - sketch.navigationGrid.minY) / sketch.navigationGrid.cell);
                int gz = (int) ((z - sketch.navigationGrid.minZ) / sketch.navigationGrid.cell);
                assert sketch.navigationGrid.blocked[gx][gy][gz] : "A* must collide with the same OSM building";
                marked++;
            }
            assert marked > 100;
            long stepBegin = System.nanoTime();
            assert solver.maxStepSeconds == 0.2f;
            for (int step = 0; step < 5; step++) solver.step(0.2f);
            double stepMs = (System.nanoTime() - stepBegin) / 5e6;
            assert world.wind.failure == null;
            FluidTest.assertSolidFaces(solver);
            world.windSnapshot = solver.latest;
            assert sketch.windView.prepareGroundColors();
            System.out.printf("Bloor-Yonge: %d volumes, %d tagged / %d floor / %d fallback heights, grid %dx%dx%d at %.2f m, load %.0f ms, step %.1f ms, divergence %.6f%n",
                city.buildings.size(), city.taggedHeights, city.levelHeights, city.defaultHeights,
                solver.nx, solver.ny, solver.nz, solver.cell, (stepBegin - begin) / 1e6, stepMs, solver.divergenceAfter);
            // Regeneration keeps the cached geography and changes the wind.
            float oldAngle = world.globalWindAngle;
            sketch.key = 'r'; sketch.keyPressed(); PathfindingTest.awaitMap(sketch);
            assert sketch.world.city == world.city;
            assert Math.abs(sketch.world.globalWindAngle - oldAngle) > 0.1f;
            // Switching back to generated terrain remains available.
            sketch.key = 'm'; sketch.keyPressed(); PathfindingTest.awaitMap(sketch);
            assert sketch.world.city == null && sketch.world.trees.length > 0;
        } finally {
            if (sketch.world != null) sketch.world.close();
            sketch.pathWorker.shutdownNow(); sketch.mapWorker.shutdownNow();
        }
        System.out.println("City scene tests passed (cached geometry, heights, projection, fluid/A* collisions, async mode switch).");
    }
}
