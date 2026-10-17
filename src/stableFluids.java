import java.util.Arrays;
import processing.core.PVector;

/**
 * 3D incompressible Stable Fluids: force/boundary driving, RK2 semi-Lagrangian
 * advection, implicit viscosity, and pressure projection (Stam, 1999).
 * https://www.dgp.toronto.edu/public_user/stam/reality/Research/pdf/ns.pdf
 *
 * MAC staggered faces are used instead of the paper's cell-centred velocities
 * so the discrete divergence and pressure gradient agree at voxel obstacles.
 * Coordinates and velocity use scene world units and seconds; +Y points down.
 */
public class stableFluids {
    final int nx, ny, nz, sx, plane, size;
    final float cell, minX, minY, minZ;
    final boolean[] solid;
    final float[][] velocity, advected, viscous;
    private final byte[][] outerNeighbors;
    private final byte[][] faceKind; // 0 solid/unused, 1 interior, 2 inlet, 3 outlet
    private final int[][] faces;
    private final int[] fluid;
    private final byte[] neighbors;
    private final float[] diagonal, pressure, rhs, residual, direction, preconditioned, product;
    private final int[] offsets;
    final float inletX, inletZ;
    final float viscosity = 12f;
    float maxStepSeconds = 0.05f;
    float divergenceBefore, divergenceAfter;
    int pressureIterations;
    volatile Snapshot latest;

    stableFluids(int nx, int ny, int nz, float cell,
            float minX, float minY, float minZ, boolean[] occupancy,
            float inletX, float inletZ) {
        if (nx < 2 || ny < 2 || nz < 2 || cell <= 0) throw new IllegalArgumentException("Invalid fluid grid");
        this.nx = nx; this.ny = ny; this.nz = nz;
        this.cell = cell; this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.inletX = inletX; this.inletZ = inletZ;
        sx = nx + 2;
        plane = sx * (ny + 2);
        size = plane * (nz + 2);
        if (occupancy.length != size) throw new IllegalArgumentException("Wrong solid mask size");
        solid = occupancy.clone();
        velocity = new float[3][size];
        advected = new float[3][size];
        viscous = new float[3][size];
        faceKind = new byte[3][size];
        outerNeighbors = new byte[3][size];
        faces = new int[3][];
        offsets = new int[]{-1, 1, -sx, sx, -plane, plane};
        neighbors = new byte[size];
        diagonal = new float[size]; pressure = new float[size]; rhs = new float[size];
        residual = new float[size]; direction = new float[size];
        preconditioned = new float[size]; product = new float[size];
        int[] fluidList = new int[nx * ny * nz];
        int count = 0;
        for (int k = 1; k <= nz; k++) for (int j = 1; j <= ny; j++) for (int i = 1; i <= nx; i++) {
            int id = index(i, j, k);
            if (solid[id]) continue;
            fluidList[count++] = id;
            for (int d = 0; d < 6; d++) {
                int ni = i + (d == 0 ? -1 : d == 1 ? 1 : 0);
                int nj = j + (d == 2 ? -1 : d == 3 ? 1 : 0);
                int nk = k + (d == 4 ? -1 : d == 5 ? 1 : 0);
                if (ni >= 1 && ni <= nx && nj >= 1 && nj <= ny && nk >= 1 && nk <= nz) {
                    if (!solid[id + offsets[d]]) {
                        neighbors[id] |= 1 << d;
                        diagonal[id]++;
                    }
                } else if (boundary(d) == 3) {
                    // Pressure is zero at the outlet face, half a cell away.
                    diagonal[id] += 2;
                }
            }
        }
        fluid = Arrays.copyOf(fluidList, count);
        buildFaces();
        for (int axis = 0; axis < 3; axis++)
            for (int id : faces[axis]) velocity[axis][id] = inlet(axis);
        enforceFaces(velocity);
        project(velocity, 300);
        // Initializing an entire wind field requires a very different pressure
        // correction from the small per-step advection/diffusion correction.
        Arrays.fill(pressure, 0);
        publish();
    }

    int index(int i, int j, int k) { return k * plane + j * sx + i; }

    private float inlet(int axis) { return axis == 0 ? inletX : axis == 2 ? inletZ : 0; }

    private byte boundary(int side) {
        // A slip top keeps horizontal wind crossing the domain. Treating the
        // entire shallow roof as a zero-pressure outlet short-circuited inflow.
        if (side == 2) return 0; // No normal flow; tangential velocity has zero gradient.
        if (side == 3) return 0; // Ground/bottom wall.
        float component = side < 2 ? inletX : inletZ;
        if (Math.abs(component) < 0.00001f) return 3; // Ambient open side.
        boolean low = side == 0 || side == 4;
        return (low == (component > 0)) ? (byte) 2 : (byte) 3;
    }

    private void buildFaces() {
        for (int axis = 0; axis < 3; axis++) {
            int[] ids = new int[size];
            int count = 0;
            for (int k = axis == 2 ? 0 : 1; k <= nz; k++)
                for (int j = axis == 1 ? 0 : 1; j <= ny; j++)
                    for (int i = axis == 0 ? 0 : 1; i <= nx; i++) {
                        int id = index(i, j, k);
                        int coordinate = axis == 0 ? i : axis == 1 ? j : k;
                        int limit = axis == 0 ? nx : axis == 1 ? ny : nz;
                        int offset = axis == 0 ? 1 : axis == 1 ? sx : plane;
                        byte kind;
                        if (coordinate == 0) kind = solid[id + offset] ? 0 : boundary(axis * 2);
                        else if (coordinate == limit) kind = solid[id] ? 0 : boundary(axis * 2 + 1);
                        else kind = solid[id] || solid[id + offset] ? 0 : (byte) 1;
                        faceKind[axis][id] = kind;
                        if (kind != 0) {
                            ids[count++] = id;
                            // Zero tangential/normal diffusion gradients at the
                            // outer domain, rather than ghost-cell drag to zero.
                            if (i == (axis == 0 ? 0 : 1)) outerNeighbors[axis][id] |= 1;
                            if (i == nx) outerNeighbors[axis][id] |= 2;
                            if (j == (axis == 1 ? 0 : 1)) outerNeighbors[axis][id] |= 4;
                            if (j == ny) outerNeighbors[axis][id] |= 8;
                            if (k == (axis == 2 ? 0 : 1)) outerNeighbors[axis][id] |= 16;
                            if (k == nz) outerNeighbors[axis][id] |= 32;
                        }
                    }
            faces[axis] = Arrays.copyOf(ids, count);
        }
    }

    void step(float seconds) {
        if (!(seconds > 0) || !Float.isFinite(seconds)) return;
        seconds = Math.min(seconds, maxStepSeconds);
        enforceFaces(velocity);
        for (int axis = 0; axis < 3; axis++) {
            if (Thread.currentThread().isInterrupted()) return;
            advect(axis, seconds);
        }
        enforceFaces(advected);
        diffuse(seconds);
        enforceFaces(viscous);
        project(viscous, 80);
        for (int axis = 0; axis < 3; axis++)
            System.arraycopy(viscous[axis], 0, velocity[axis], 0, size);
        publish();
    }

    private void enforceFaces(float[][] field) {
        for (int axis = 0; axis < 3; axis++) {
            for (int id : faces[axis]) {
                if (faceKind[axis][id] == 2) field[axis][id] = inlet(axis);
            }
        }
        // Invalid/solid faces are never written by advection, diffusion, or
        // pressure correction and remain exactly zero in all buffers.
    }

    private void advect(int axis, float seconds) {
        float[] wind = new float[3], midpoint = new float[3], departure = new float[3];
        float dt = seconds / cell;
        for (int id : faces[axis]) {
            int k = id / plane, j = id % plane / sx, i = id % sx;
            float x = i - (axis == 0 ? 0 : 0.5f);
            float y = j - (axis == 1 ? 0 : 0.5f);
            float z = k - (axis == 2 ? 0 : 0.5f);
            sampleLocal(velocity, x, y, z, wind);
            clipTrace(x, y, z, x - wind[0] * dt * 0.5f,
                y - wind[1] * dt * 0.5f, z - wind[2] * dt * 0.5f, midpoint);
            sampleLocal(velocity, midpoint[0], midpoint[1], midpoint[2], wind);
            clipTrace(x, y, z, x - wind[0] * dt, y - wind[1] * dt, z - wind[2] * dt, departure);
            if (outsideInlet(departure[0], departure[1], departure[2]))
                advected[axis][id] = inlet(axis);
            else advected[axis][id] = component(velocity[axis], axis,
                departure[0], departure[1], departure[2]);
        }
    }

    private boolean outsideInlet(float x, float y, float z) {
        return (x < 0 && boundary(0) == 2) || (x > nx && boundary(1) == 2)
            || (z < 0 && boundary(4) == 2) || (z > nz && boundary(5) == 2);
    }

    // Clip backtraces at the first solid voxel: never sample through a tree/wall.
    private void clipTrace(float x, float y, float z, float tx, float ty, float tz, float[] result) {
        float dx = tx - x, dy = ty - y, dz = tz - z;
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) * 3));
        float previous = 0;
        for (int step = 1; step <= steps; step++) {
            float t = (float) step / steps;
            if (solidLocal(x + dx * t, y + dy * t, z + dz * t)) {
                float low = previous, high = t;
                for (int b = 0; b < 8; b++) {
                    float mid = (low + high) * 0.5f;
                    if (solidLocal(x + dx * mid, y + dy * mid, z + dz * mid)) high = mid;
                    else low = mid;
                }
                tx = x + dx * low; ty = y + dy * low; tz = z + dz * low;
                break;
            }
            previous = t;
        }
        result[0] = tx; result[1] = ty; result[2] = tz;
    }

    private void diffuse(float seconds) {
        float alpha = viscosity * seconds / (cell * cell);
        for (int axis = 0; axis < 3; axis++) {
            float[] source = advected[axis], target = viscous[axis];
            System.arraycopy(source, 0, target, 0, size);
            // Gauss-Seidel solve of (I - nu*dt*Laplacian) u = advected u.
            for (int iteration = 0; iteration < 8; iteration++) {
                for (int id : faces[axis]) {
                    if (faceKind[axis][id] == 2) { target[id] = inlet(axis); continue; }
                    float sum = 0;
                    int mask = outerNeighbors[axis][id];
                    for (int d = 0; d < 6; d++)
                        sum += (mask & (1 << d)) != 0 ? target[id] : target[id + offsets[d]];
                    target[id] = (source[id] + alpha * sum) / (1 + 6 * alpha);
                }
            }
        }
    }

    /** Compatible MAC divergence/gradient, with a Jacobi-preconditioned CG solve. */
    void project(float[][] field, int maxIterations) {
        enforceFaces(field);
        double rhsNorm = 0;
        for (int id : fluid) {
            rhs[id] = -flux(field, id);
            rhsNorm += rhs[id] * (double) rhs[id];
        }
        divergenceBefore = fluid.length == 0 ? 0 : (float) Math.sqrt(rhsNorm / fluid.length) / cell;
        applyPressure(pressure, product);
        double rz = 0;
        for (int id : fluid) {
            residual[id] = rhs[id] - product[id];
            preconditioned[id] = diagonal[id] > 0 ? residual[id] / diagonal[id] : 0;
            direction[id] = preconditioned[id];
            rz += residual[id] * (double) preconditioned[id];
        }
        double tolerance = Math.max(1e-12, rhsNorm * 1e-8);
        pressureIterations = 0;
        for (int iteration = 0; iteration < maxIterations && rz > 1e-16; iteration++) {
            if (Thread.currentThread().isInterrupted()) return;
            applyPressure(direction, product);
            double denominator = 0;
            for (int id : fluid) denominator += direction[id] * (double) product[id];
            if (denominator <= 1e-20) break;
            float alpha = (float) (rz / denominator);
            double norm = 0, nextRz = 0;
            for (int id : fluid) {
                pressure[id] += alpha * direction[id];
                residual[id] -= alpha * product[id];
                norm += residual[id] * (double) residual[id];
                preconditioned[id] = diagonal[id] > 0 ? residual[id] / diagonal[id] : 0;
                nextRz += residual[id] * (double) preconditioned[id];
            }
            pressureIterations++;
            if (norm < tolerance) break;
            float beta = (float) (nextRz / rz);
            for (int id : fluid) direction[id] = preconditioned[id] + beta * direction[id];
            rz = nextRz;
        }
        for (int axis = 0; axis < 3; axis++) {
            int offset = axis == 0 ? 1 : axis == 1 ? sx : plane;
            for (int id : faces[axis]) {
                byte kind = faceKind[axis][id];
                if (kind == 2) continue;
                if (kind == 1) field[axis][id] -= pressure[id + offset] - pressure[id];
                else {
                    int coordinate = axis == 0 ? id % sx : axis == 1 ? id % plane / sx : id / plane;
                    field[axis][id] += coordinate == 0 ? -2 * pressure[id + offset] : 2 * pressure[id];
                }
            }
        }
        double norm = 0;
        for (int id : fluid) { float div = flux(field, id); norm += div * (double) div; }
        divergenceAfter = fluid.length == 0 ? 0 : (float) Math.sqrt(norm / fluid.length) / cell;
    }

    private float flux(float[][] field, int id) {
        return field[0][id] - field[0][id - 1]
            + field[1][id] - field[1][id - sx] + field[2][id] - field[2][id - plane];
    }

    private void applyPressure(float[] input, float[] output) {
        for (int id : fluid) {
            float value = diagonal[id] * input[id];
            int mask = neighbors[id];
            for (int d = 0; d < 6; d++) if ((mask & (1 << d)) != 0) value -= input[id + offsets[d]];
            output[id] = value;
        }
    }

    private void sampleLocal(float[][] field, float x, float y, float z, float[] result) {
        result[0] = component(field[0], 0, x, y, z);
        result[1] = component(field[1], 1, x, y, z);
        result[2] = component(field[2], 2, x, y, z);
    }

    private float component(float[] field, int axis, float x, float y, float z) {
        return interpolate(field, nx, ny, nz, sx, plane,
            x + (axis == 0 ? 0 : 0.5f), y + (axis == 1 ? 0 : 0.5f), z + (axis == 2 ? 0 : 0.5f), axis);
    }

    private static float interpolate(float[] field, int nx, int ny, int nz, int sx, int plane,
            float x, float y, float z, int axis) {
        x = clamp(x, axis == 0 ? 0 : 1, nx);
        y = clamp(y, axis == 1 ? 0 : 1, ny);
        z = clamp(z, axis == 2 ? 0 : 1, nz);
        int i = (int) x, j = (int) y, k = (int) z;
        int i1 = Math.min(i + 1, nx), j1 = Math.min(j + 1, ny), k1 = Math.min(k + 1, nz);
        float a = x - i, b = y - j, c = z - k;
        int p00 = k * plane + j * sx, p01 = k * plane + j1 * sx;
        int p10 = k1 * plane + j * sx, p11 = k1 * plane + j1 * sx;
        float low = mix(mix(field[p00 + i], field[p00 + i1], a), mix(field[p01 + i], field[p01 + i1], a), b);
        float high = mix(mix(field[p10 + i], field[p10 + i1], a), mix(field[p11 + i], field[p11 + i1], a), b);
        return mix(low, high, c);
    }

    private boolean solidLocal(float x, float y, float z) {
        if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) return false;
        return solid[index((int) x + 1, (int) y + 1, (int) z + 1)];
    }

    void publish() { latest = new Snapshot(this); }

    /** Immutable face velocities; the simulation never mutates published arrays. */
    static final class Snapshot {
        final int nx, ny, nz, sx, plane;
        final float cell, minX, minY, minZ;
        final boolean[] solid;
        private final float[][] wind;
        Snapshot(stableFluids solver) {
            nx = solver.nx; ny = solver.ny; nz = solver.nz; sx = solver.sx; plane = solver.plane;
            cell = solver.cell; minX = solver.minX; minY = solver.minY; minZ = solver.minZ;
            solid = solver.solid;
            wind = new float[][]{solver.velocity[0].clone(), solver.velocity[1].clone(), solver.velocity[2].clone()};
        }
        boolean contains(float x, float y, float z) {
            return x >= minX && x < minX + nx * cell && y >= minY && y < minY + ny * cell
                && z >= minZ && z < minZ + nz * cell;
        }
        boolean isSolid(float x, float y, float z) {
            if (!contains(x, y, z)) return false;
            return solid[((int) ((z - minZ) / cell) + 1) * plane
                + ((int) ((y - minY) / cell) + 1) * sx + (int) ((x - minX) / cell) + 1];
        }
        PVector sample(float x, float y, float z, PVector result) {
            if (!contains(x, y, z) || isSolid(x, y, z)) return result.set(0, 0, 0);
            float gx = (x - minX) / cell, gy = (y - minY) / cell, gz = (z - minZ) / cell;
            return result.set(interpolate(wind[0], nx, ny, nz, sx, plane, gx, gy + 0.5f, gz + 0.5f, 0),
                interpolate(wind[1], nx, ny, nz, sx, plane, gx + 0.5f, gy, gz + 0.5f, 1),
                interpolate(wind[2], nx, ny, nz, sx, plane, gx + 0.5f, gy + 0.5f, gz, 2));
        }
    }

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
}
