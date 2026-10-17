import java.util.Random;
import processing.core.PApplet;
import processing.core.PVector;

public class FluidTest {
    static stableFluids boxFlow() {
        int nx = 26, ny = 14, nz = 22;
        int sx = nx + 2, plane = sx * (ny + 2);
        boolean[] solid = new boolean[plane * (nz + 2)];
        for (int k = 1; k <= nz; k++) for (int i = 1; i <= nx; i++) {
            for (int j = ny - 1; j <= ny; j++) solid[k * plane + j * sx + i] = true;
            if (i >= 11 && i <= 14 && k >= 9 && k <= 14)
                for (int j = 5; j <= ny; j++) solid[k * plane + j * sx + i] = true;
        }
        return new stableFluids(nx, ny, nz, 1, 0, 0, 0, solid, 4, 0);
    }

    public static void main(String[] args) {
        stableFluids solver = boxFlow();
        assert solver.divergenceAfter < solver.divergenceBefore * 0.001f
            : "Projection must reduce divergence: " + solver.divergenceBefore + " -> " + solver.divergenceAfter;
        PVector center = solver.latest.sample(12, 7, 11, new PVector());
        assert center.magSq() == 0 : "Solid obstacle must have no sampled velocity";
        float vertical = 0, lateral = 0;
        for (int k = 1; k <= solver.nz; k++) for (int j = 1; j <= solver.ny; j++) for (int i = 1; i <= solver.nx; i++) {
            PVector velocity = solver.latest.sample(i - 0.5f, j - 0.5f, k - 0.5f, new PVector());
            vertical = Math.max(vertical, Math.abs(velocity.y));
            lateral = Math.max(lateral, Math.abs(velocity.z));
        }
        assert vertical > 0.1f && lateral > 0.1f : "Flow must turn in 3D around the obstacle";
        assertSolidFaces(solver);

        // Perturb only known open faces, then compare the compatible divergence.
        Random random = new Random(17);
        for (int k = 2; k < solver.nz; k++) for (int j = 2; j < solver.ny; j++) for (int i = 2; i < solver.nx; i++) {
            int id = solver.index(i, j, k);
            if (solver.solid[id]) continue;
            int[] offset = {1, solver.sx, solver.plane};
            for (int axis = 0; axis < 3; axis++) {
                if (!solver.solid[id + offset[axis]]) solver.velocity[axis][id] += random.nextFloat() - 0.5f;
            }
        }
        solver.project(solver.velocity, 180);
        assert solver.divergenceAfter < solver.divergenceBefore * 0.001f;
        solver.publish();
        stableFluids.Snapshot old = solver.latest;
        PVector oldSample = old.sample(5, 7, 10, new PVector());
        for (int step = 0; step < 40; step++) solver.step(1f / 30);
        PVector same = old.sample(5, 7, 10, new PVector());
        assert PVector.dist(same, oldSample) == 0 : "Published snapshots must stay immutable";
        for (float[] field : solver.velocity) for (float value : field)
            assert Float.isFinite(value) && Math.abs(value) < 100 : "Velocity must remain bounded";
        assertSolidFaces(solver);
        assert solver.divergenceAfter < 0.005f : "Incompressibility must survive advection and viscosity";
        testWindDirections();
        testViscousDecay();
        testLeafCoupling();
        benchmarkMap();
        System.out.printf("Fluid tests passed; obstacle deflection y=%.3f z=%.3f, final divergence %.6f%n",
            vertical, lateral, solver.divergenceAfter);
    }

    static void testWindDirections() {
        for (int signX : new int[]{-1, 1}) for (int signZ : new int[]{-1, 1}) {
            stableFluids solver = boxFlow();
            solver = new stableFluids(solver.nx, solver.ny, solver.nz, 1, 0, 0, 0,
                solver.solid, signX * 4, signZ * 2);
            for (int i = 0; i < 4; i++) solver.step(1f / 30);
            assert solver.divergenceAfter < 0.005f : "Projection must support every incoming quadrant";
            assertSolidFaces(solver);
            PVector inlet = solver.latest.sample(signX > 0 ? 0.01f : solver.nx - 0.01f,
                2, 4, new PVector());
            assert Math.signum(inlet.x) == signX;
        }
    }

    static void testViscousDecay() {
        stableFluids solver = new stableFluids(12, 12, 12, 1, 0, 0, 0,
            new boolean[14 * 14 * 14], 0, 0);
        solver.velocity[0][solver.index(6, 6, 6)] = 4;
        solver.step(1f / 30);
        double energy = 0;
        for (float[] component : solver.velocity) for (float value : component) energy += value * value;
        assert energy > 0 && energy < 16 : "Unforced impulse must dissipate, not gain kinetic energy";
    }

    static void assertSolidFaces(stableFluids solver) {
        for (int k = 1; k <= solver.nz; k++) for (int j = 1; j <= solver.ny; j++) for (int i = 1; i <= solver.nx; i++) {
            int id = solver.index(i, j, k);
            if (!solver.solid[id]) continue;
            assert solver.velocity[0][id] == 0 && solver.velocity[0][id - 1] == 0;
            assert solver.velocity[1][id] == 0 && solver.velocity[1][id - solver.sx] == 0;
            assert solver.velocity[2][id] == 0 && solver.velocity[2][id - solver.plane] == 0;
        }
    }

    static void testLeafCoupling() {
        PApplet app = new PApplet();
        map world = new map(app);
        stableFluids solver = new stableFluids(10, 10, 10, 20, -100, -250, -100,
            new boolean[12 * 12 * 12], 0, 0);
        // A known 3D field proves leaves sample all three fluid components.
        for (int axis = 0; axis < 3; axis++)
            java.util.Arrays.fill(solver.velocity[axis], axis == 0 ? 30 : axis == 1 ? -40 : 15);
        solver.publish();
        world.windSnapshot = solver.latest;
        world.spawnLeaf(0, -150, 0);
        map.Leaf leaf = world.leaves[0];
        assert leaf.active;
        for (int i = 0; i < 10; i++) world.updateLeaves(0.02f);
        assert leaf.x > 5 && leaf.z > 2 && leaf.y < -150
            : "Leaves must follow the local simulated wind, including vertical lift";
    }

    static void benchmarkMap() {
        PApplet app = new PApplet();
        app.noiseSeed(17); app.randomSeed(17);
        map world = new map(app);
        long begin = System.nanoTime();
        world.startWind();
        world.wind.close();
        // Wait for the worker to exit before operating on its solver directly.
        world.wind.awaitStopped();
        stableFluids solver = world.wind.solver;
        long start = System.nanoTime();
        for (int i = 0; i < 8; i++) solver.step(1f / 30);
        double milliseconds = (System.nanoTime() - start) / 8e6;
        assert world.wind.failure == null;
        assertSolidFaces(solver);
        map.Tree tree = world.trees[0];
        float ground = world.groundHeightAt(tree.x, tree.z);
        assert solver.latest.isSolid(tree.x, ground - tree.baseHeight, tree.z) : "Canopy must be rasterized";
        assert solver.latest.isSolid(tree.x, ground - tree.baseHeight / 2, tree.z) : "Trunk must be rasterized";
        assert solver.latest.isSolid(0, world.surfaceHeightAt(0, 0) + 5, 0) : "Terrain must be solid";
        System.out.printf("Map fluid grid %dx%dx%d, initial setup %.1f ms, step %.1f ms, divergence %.6f -> %.6f%n",
            solver.nx, solver.ny, solver.nz, (start - begin) / 1e6, milliseconds,
            solver.divergenceBefore, solver.divergenceAfter);
    }
}
