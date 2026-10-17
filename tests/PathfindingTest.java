import java.util.*;
import processing.core.PApplet;
import processing.core.PVector;

// Run with assertions enabled; no Processing window is needed.
public class PathfindingTest {
    public static void main(String[] args) {
        testSearch();
        testTerrainPicking();
        testUnprojection();
        testClickSelection();
        System.out.println("Pathfinding tests passed (3D search, collisions, raycasting, unprojection, asynchronous click selection).");
    }

    static void testUnprojection() {
        src sketch = new src();
        try {
            sketch.width = 800;
            sketch.height = 600;
            sketch.inverseViewProjection = new processing.core.PMatrix3D();
            PVector near = sketch.unproject(400, 300, -1);
            PVector far = sketch.unproject(400, 300, 1);
            assert near.x == 0 && near.y == 0 && near.z == -1;
            assert far.x == 0 && far.y == 0 && far.z == 1;
            // A non-unit homogeneous W must be divided out, not discarded.
            sketch.inverseViewProjection.m33 = 2;
            PVector corner = sketch.unproject(800, 0, 1);
            assert corner.x == 0.5f && corner.y == 0.5f && corner.z == 0.5f;
        } finally {
            if (sketch.world != null) sketch.world.close();
            sketch.pathWorker.shutdownNow();
            sketch.mapWorker.shutdownNow();
        }
    }

    static void testClickSelection() {
        src sketch = new src();
        try {
            sketch.width = 800;
            sketch.height = 600;
            sketch.cityMode = false;
            map world = new map(new PApplet());
            world.cols = world.rows = 4;
            world.w = world.h = 60;
            world.terrain = new float[4][4];
            world.trees = new map.Tree[0];
            sketch.world = world;
            sketch.navigationGrid = new navigation(world);
            sketch.windView = new windVisualization(world);
            // Map screen X/Y to terrain X/Z, and near/far depth to vertical Y.
            sketch.inverseViewProjection = new processing.core.PMatrix3D(
                30, 0, 0, 0,
                0, 0, 100, 0,
                0, 30, 0, 0,
                0, 0, 0, 1);
            // Leave cached mouse coordinates deliberately wrong. Picking must
            // use each press event's coordinates, including rapid clicks.
            click(sketch, 200, 300);
            assert sketch.start != null && sketch.goal == null;
            click(sketch, 600, 300);
            assert sketch.goal != null && sketch.path.size() > 1;
            assert sketch.pathStatus.startsWith("Route complete:");
            int[] savedStart = sketch.start, savedGoal = sketch.goal;
            click(sketch, 1600, 300); // Outside mesh must preserve the route.
            assert sketch.start == savedStart && sketch.goal == savedGoal;
            click(sketch, 400, 300); // Third valid click begins a new route.
            assert sketch.start != savedStart && sketch.goal == null && sketch.path.isEmpty();
            sketch.key = 'r';
            sketch.keyPressed();
            awaitMap(sketch);
            assert sketch.start == null && sketch.goal == null && sketch.pendingSelection == null;
            assert sketch.world != world && sketch.navigationGrid.world == sketch.world;
            assert sketch.windView != null && sketch.inverseViewProjection == null;
            map regenerated = sketch.world;
            float previousHeight = regenerated.terrain[20][20];
            float previousWindAngle = regenerated.globalWindAngle;
            sketch.keyPressed();
            awaitMap(sketch);
            assert sketch.world != regenerated;
            float angleDifference = Math.abs(sketch.world.globalWindAngle - previousWindAngle);
            angleDifference = Math.min(angleDifference, PApplet.TWO_PI - angleDifference);
            assert angleDifference >= PApplet.QUARTER_PI - 0.0001f : "A new map needs a new wind direction";
            assert Math.abs(sketch.world.wind.solver.inletX
                - Math.cos(sketch.world.globalWindAngle) * sketch.world.globalWindStrength * 300) < 0.001;
            assert Math.abs(sketch.world.wind.solver.inletZ
                - Math.sin(sketch.world.globalWindAngle) * sketch.world.globalWindStrength * 300) < 0.001;
            assert sketch.world.terrain[20][20] != previousHeight : "R must change the terrain noise seed";
        } finally {
            if (sketch.world != null) sketch.world.close();
            sketch.pathWorker.shutdownNow();
            sketch.mapWorker.shutdownNow();
        }
    }

    static void awaitMap(src sketch) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (sketch.pendingMap != null && !sketch.pendingMap.isDone() && System.nanoTime() < deadline) {
            try { Thread.sleep(2); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        assert sketch.pendingMap != null && sketch.pendingMap.isDone() : "Map worker must complete";
        sketch.finishMap();
        assert !sketch.pathStatus.contains("failed") : sketch.pathStatus;
    }

    static void click(src sketch, int x, int y) {
        sketch.mousePressed(new processing.event.MouseEvent(null, 0,
            processing.event.MouseEvent.PRESS, 0, x, y, PApplet.LEFT, 1));
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (sketch.pendingSelection != null && !sketch.pendingSelection.isDone()
                && System.nanoTime() < deadline) Thread.yield();
        assert sketch.pendingSelection != null && sketch.pendingSelection.isDone()
            : "Selection worker must complete";
        sketch.finishSelection();
    }

    static void testSearch() {
        boolean[][][] grid = new boolean[5][3][5];
        // A wall that can only be crossed by changing altitude.
        for (int z = 0; z < 5; z++) grid[2][2][z] = true;
        List<int[]> path = astar.findPath(grid, 0, 2, 2, 4, 2, 2);
        assert path.size() == 7 : "Must go up and over the wall";
        assert path.stream().anyMatch(n -> n[1] < 2);
        for (int i = 0; i < path.size(); i++) {
            int[] n = path.get(i);
            assert !grid[n[0]][n[1]][n[2]];
            if (i > 0) {
                int[] p = path.get(i - 1);
                assert Math.abs(n[0] - p[0]) + Math.abs(n[1] - p[1]) + Math.abs(n[2] - p[2]) == 1;
            }
        }
        assert astar.findPath(grid, 0, 2, 2, 0, 2, 2).size() == 1;
        assert astar.findPath(grid, -1, 0, 0, 0, 0, 0).isEmpty();
        assert astar.findPath(grid, 0, 2, 2, 2, 2, 2).isEmpty();
        for (int y = 0; y < 3; y++)
            for (int z = 0; z < 5; z++) grid[2][y][z] = true;
        assert astar.findPath(grid, 0, 2, 2, 4, 2, 2).isEmpty();

        // Compare optimal path lengths with BFS across deterministic random grids.
        Random random = new Random(17);
        for (int trial = 0; trial < 80; trial++) {
            boolean[][][] randomGrid = new boolean[6][4][6];
            for (int x = 0; x < 6; x++)
                for (int y = 0; y < 4; y++)
                    for (int z = 0; z < 6; z++) randomGrid[x][y][z] = random.nextFloat() < 0.3;
            randomGrid[0][0][0] = randomGrid[5][3][5] = false;
            List<int[]> found = astar.findPath(randomGrid, 0, 0, 0, 5, 3, 5);
            assert (found.isEmpty() ? -1 : found.size() - 1) == bfs(randomGrid);
        }
    }

    static int bfs(boolean[][][] grid) {
        Queue<int[]> queue = new ArrayDeque<>();
        boolean[][][] seen = new boolean[6][4][6];
        queue.add(new int[]{0, 0, 0, 0});
        seen[0][0][0] = true;
        while (!queue.isEmpty()) {
            int[] n = queue.remove();
            if (n[0] == 5 && n[1] == 3 && n[2] == 5) return n[3];
            for (int axis = 0; axis < 3; axis++) {
                for (int sign : new int[]{-1, 1}) {
                    int[] next = n.clone();
                    next[axis] += sign;
                    int x = next[0], y = next[1], z = next[2];
                    if (x < 0 || x >= 6 || y < 0 || y >= 4 || z < 0 || z >= 6
                            || grid[x][y][z] || seen[x][y][z]) continue;
                    seen[x][y][z] = true;
                    next[3]++;
                    queue.add(next);
                }
            }
        }
        return -1;
    }

    static void testTerrainPicking() {
        PApplet processing = new PApplet();
        processing.randomSeed(17);
        processing.noiseSeed(17);
        map world = new map(processing);
        world.cols = world.rows = 4;
        world.w = world.h = 60;
        world.terrain = new float[4][4];
        world.trees = new map.Tree[0];
        navigation nav = new navigation(world);
        PVector hit = nav.raycast(new PVector(-15, -100, -15), new PVector(0, 1, 0));
        assert hit != null && Math.abs(hit.y) < 0.001;
        assert nav.raycast(new PVector(-100, -100, -100), new PVector(0, 1, 0)) == null;
        assert nav.raycast(new PVector(-15, -100, -15), new PVector(0, -1, 0)) == null;
        int[] endpoint = nav.endpoint(hit);
        assert endpoint != null && !nav.blocked[endpoint[0]][endpoint[1]][endpoint[2]];
        assert nav.position(endpoint).y < hit.y;
        // Verify picking on a sloped triangle rather than a flat ground plane.
        world.terrain[1][0] = -20;
        world.terrain[1][1] = -20;
        hit = nav.raycast(new PVector(-20, -100, -20), new PVector(0, 1, 0));
        assert hit != null && Math.abs(hit.y + 10) < 0.001;
        nav = new navigation(world);
        assert nav.blocked[0][(int) ((-10 - nav.minY) / nav.cell)][0];

        map.Tree tree = world.new Tree();
        tree.x = tree.z = 0;
        tree.baseHeight = 80;
        tree.swayAmp = 0.16f;
        world.trees = new map.Tree[]{tree};
        nav = new navigation(world);
        int y = (int) ((-80 - nav.minY) / nav.cell);
        assert nav.blocked[1][y][1] : "Tree canopy must occupy air voxels";
    }
}
