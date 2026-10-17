import processing.core.PVector;

/** Covers late-time loss of ground wind that short stability tests missed. */
public class SustainedWindTest {
    static stableFluids forest() {
        int nx = 32, ny = 12, nz = 24, sx = nx + 2, plane = sx * (ny + 2);
        boolean[] solid = new boolean[plane * (nz + 2)];
        for (int k = 1; k <= nz; k++) for (int i = 1; i <= nx; i++) {
            for (int j = ny - 1; j <= ny; j++) solid[k * plane + j * sx + i] = true;
            if (i >= 6 && i <= 26 && i % 5 == 1 && k >= 4 && k <= 21 && k % 5 == 1) {
                for (int j = ny - 5; j <= ny - 2; j++) solid[k * plane + j * sx + i] = true;
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                    solid[(k + dz) * plane + (ny - 5) * sx + i + dx] = true;
            }
        }
        return new stableFluids(nx, ny, nz, 20, 0, 0, 0, solid, 100, 25);
    }

    static float nearGroundSpeed(stableFluids solver) {
        PVector velocity = new PVector();
        float sum = 0; int samples = 0;
        for (int k = 3; k < solver.nz - 2; k++) for (int i = 4; i < solver.nx - 2; i++) {
            int j = solver.ny - 3;
            if (solver.solid[solver.index(i, j, k)]) continue;
            solver.latest.sample((i - 0.5f) * solver.cell, (j - 0.5f) * solver.cell,
                (k - 0.5f) * solver.cell, velocity);
            sum += velocity.mag(); samples++;
        }
        return sum / samples;
    }

    static void testUniformFlow() {
        stableFluids uniform = new stableFluids(12, 8, 12, 20, 0, 0, 0,
            new boolean[14 * 10 * 14], 100, 25);
        for (int step = 0; step < 120; step++) uniform.step(0.05f);
        PVector velocity = uniform.latest.sample(120, 30, 120, new PVector());
        assert Math.abs(velocity.x - 100) < 0.01 && Math.abs(velocity.z - 25) < 0.01
            : "Outer diffusion ghosts must not drain unobstructed uniform wind: " + velocity;
    }

    public static void main(String[] args) {
        testUniformFlow();
        stableFluids solver = forest();
        float initial = nearGroundSpeed(solver), midway = 0;
        for (int step = 0; step < 600; step++) {
            solver.step(0.05f); // 30 seconds: almost five full domain crossings.
            if (step == 299) midway = nearGroundSpeed(solver);
        }
        float end = nearGroundSpeed(solver);
        System.out.printf("Sustained forest wind: initial %.2f, 15s %.2f, 30s %.2f units/s%n", initial, midway, end);
        float inletSpeed = (float) Math.hypot(solver.inletX, solver.inletZ);
        assert end > inletSpeed * 0.5f : "This sparse forest must retain ground wind under continuous inflow";
        assert end >= midway * 0.85f : "Ground speed must settle rather than continue draining";
        assert solver.divergenceAfter < 0.005 : "The correction must remain incompressible";
        for (int i = 1; i <= solver.nx; i++) for (int k = 1; k <= solver.nz; k++)
            assert solver.velocity[1][solver.index(i, 0, k)] == 0 : "Wind must not escape through the slip roof";
        FluidTest.assertSolidFaces(solver);
        System.out.println("Sustained wind tests passed.");
    }
}
