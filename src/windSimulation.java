import java.util.concurrent.*;

/** Owns a background solver and rasterizes the map into stationary solid voxels. */
public class windSimulation implements AutoCloseable {
    final stableFluids solver;
    private final ScheduledExecutorService worker;
    private long previousNanos;
    volatile Throwable failure;

    windSimulation(map world) {
        int nx = 64;
        float span = (world.cols - 1) * world.scale;
        float cell = span / nx;
        float minX = -world.w / 2f, minZ = -world.h / 2f;
        float minY = world.airTop();
        int ny = (int) Math.ceil((world.terrainHeight / 2 + 2 * cell - minY) / cell);
        int nz = (int) Math.ceil((world.rows - 1) * world.scale / cell);
        int sx = nx + 2, plane = sx * (ny + 2);
        boolean[] solid = new boolean[plane * (nz + 2)];
        for (int k = 1; k <= nz; k++) for (int i = 1; i <= nx; i++) {
            float x0 = minX + (i - 1) * cell, z0 = minZ + (k - 1) * cell;
            float highest = Math.min(Math.min(world.surfaceHeightAt(x0, z0), world.surfaceHeightAt(x0 + cell, z0)),
                Math.min(world.surfaceHeightAt(x0, z0 + cell), world.surfaceHeightAt(x0 + cell, z0 + cell)));
            // Bound every mesh triangle touched by this footprint, including edge crossings.
            int xStart = Math.max(0, (int) Math.floor((x0 - minX) / world.scale));
            int xEnd = Math.min(world.cols - 1, (int) Math.ceil((x0 + cell - minX) / world.scale));
            int zStart = Math.max(0, (int) Math.floor((z0 - minZ) / world.scale));
            int zEnd = Math.min(world.rows - 1, (int) Math.ceil((z0 + cell - minZ) / world.scale));
            for (int x = xStart; x <= xEnd; x++) for (int z = zStart; z <= zEnd; z++)
                highest = Math.min(highest, world.terrain[x][z]);
            for (int j = 1; j <= ny; j++)
                solid[k * plane + j * sx + i] = minY + j * cell >= highest;
        }
        for (map.Tree tree : world.trees) {
            float ground = world.groundHeightAt(tree.x, tree.z), top = ground - tree.baseHeight;
            float sway = tree.swayAmp * 24; // Same bounded envelope as tree animation/pathfinding.
            float trunk = 3 + sway, canopy = 18 + sway;
            int i0 = Math.max(1, (int) Math.floor((tree.x - canopy - minX) / cell) + 1);
            int i1 = Math.min(nx, (int) Math.floor((tree.x + canopy - minX) / cell) + 1);
            int k0 = Math.max(1, (int) Math.floor((tree.z - canopy - minZ) / cell) + 1);
            int k1 = Math.min(nz, (int) Math.floor((tree.z + canopy - minZ) / cell) + 1);
            for (int k = k0; k <= k1; k++) for (int i = i0; i <= i1; i++) {
                float dx = Math.max(0, Math.abs(minX + (i - 0.5f) * cell - tree.x) - cell / 2);
                float dz = Math.max(0, Math.abs(minZ + (k - 0.5f) * cell - tree.z) - cell / 2);
                for (int j = 1; j <= ny; j++) {
                    float y = minY + (j - 0.5f) * cell;
                    float dy = Math.max(0, Math.abs(y - top) - cell / 2);
                    if (dx * dx + dy * dy + dz * dz <= canopy * canopy
                            || (dx * dx + dz * dz <= trunk * trunk && y + cell / 2 >= top && y - cell / 2 <= ground))
                        solid[k * plane + j * sx + i] = true;
                }
            }
        }
        if (world.city != null) {
            for (CityScene.Building building : world.city.buildings) {
                int i0 = Math.max(1, (int) Math.floor((building.bounds.getMinX() - minX) / cell) + 1);
                int i1 = Math.min(nx, (int) Math.floor((building.bounds.getMaxX() - minX) / cell) + 1);
                int k0 = Math.max(1, (int) Math.floor((building.bounds.getMinY() - minZ) / cell) + 1);
                int k1 = Math.min(nz, (int) Math.floor((building.bounds.getMaxY() - minZ) / cell) + 1);
                int j0 = Math.max(1, (int) Math.floor((-building.height - minY) / cell) + 1);
                int j1 = Math.min(ny, (int) Math.floor((-building.bottom - minY) / cell) + 1);
                for (int k = k0; k <= k1; k++) for (int i = i0; i <= i1; i++) {
                    if (!building.overlaps(minX + (i - 1) * cell, minZ + (k - 1) * cell, cell)) continue;
                    for (int j = j0; j <= j1; j++) solid[k * plane + j * sx + i] = true;
                }
            }
        }
        float speed = world.inletSpeed();
        solver = new stableFluids(nx, ny, nz, cell, minX, minY, minZ, solid,
            (float) Math.cos(world.globalWindAngle) * speed, (float) Math.sin(world.globalWindAngle) * speed);
        // The taller city grid updates less frequently. Its lower prescribed
        // wind speed permits a larger stable step while remaining below a cell
        // of advection, so simulation time need not run at forest-step speed.
        if (world.city != null) solver.maxStepSeconds = 0.2f;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "stable-fluids");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        previousNanos = System.nanoTime();
        worker.scheduleWithFixedDelay(() -> {
            try {
                long now = System.nanoTime();
                float dt = Math.min(solver.maxStepSeconds, (now - previousNanos) * 1e-9f);
                previousNanos = now;
                solver.step(dt);
            } catch (Throwable exception) {
                failure = exception;
                exception.printStackTrace();
                worker.shutdown();
            }
        }, 16, 16, TimeUnit.MILLISECONDS);
    }

    void awaitStopped() {
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fluid worker did not stop");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Override public void close() { worker.shutdownNow(); }
}
