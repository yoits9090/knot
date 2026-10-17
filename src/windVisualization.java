import processing.core.PApplet;
import processing.core.PVector;

/** Moving particle pathlines; every retained point records actual fluid advection. */
public class windVisualization {
    private static final int PARTICLES = 180;
    private static final int POINTS = 128;
    private static final float SAMPLE_SECONDS = 0.04f;
    private static final int[] PALETTE = {
        0xff2539ed, 0xff00bdff, 0xff27df84, 0xffe3ed39, 0xffff962b, 0xffef3238
    };
    private final map world;
    final Trail[] trails = new Trail[PARTICLES];
    private final java.util.Random random = new java.util.Random(42); // Seed locations only.
    private final PVector wind = new PVector(), middleWind = new PVector();
    final float maxSpeed;
    private final float sampleSeconds, minimumSpeed;
    private final int[][] groundColors;
    private stableFluids.Snapshot groundField;

    static class Trail {
        final PVector position = new PVector();
        final float[] x = new float[POINTS], y = new float[POINTS], z = new float[POINTS];
        final float[] speed = new float[POINTS];
        int head, count;
        float length, clock, stalledSeconds;

        int point(int chronological) { return (head - count + 1 + chronological + POINTS) % POINTS; }

        void append(float velocity) {
            if (count == POINTS) {
                int oldest = (head + 1) % POINTS, next = (oldest + 1) % POINTS;
                length -= distance(x[oldest], y[oldest], z[oldest], x[next], y[next], z[next]);
            }
            if (count > 0) length += distance(position.x, position.y, position.z, x[head], y[head], z[head]);
            head = count == 0 ? 0 : (head + 1) % POINTS;
            x[head] = position.x; y[head] = position.y; z[head] = position.z;
            speed[head] = velocity;
            count = Math.min(POINTS, count + 1);
            length = Math.max(0, length);
        }
    }

    windVisualization(map world) {
        this.world = world;
        sampleSeconds = world.city == null ? SAMPLE_SECONDS : 0.15f;
        minimumSpeed = world.city == null ? 2 : Math.max(0.05f,world.inletSpeed()*0.03f);
        groundColors = new int[world.cols][world.rows];
        float inletSpeed = world.wind == null ? world.inletSpeed()
            : PApplet.sqrt(world.wind.solver.inletX * world.wind.solver.inletX
                + world.wind.solver.inletZ * world.wind.solver.inletZ);
        float scaleStep = world.city == null ? 10 : 2;
        maxSpeed = Math.max(scaleStep, (float) Math.ceil(inletSpeed * 1.25f / scaleStep) * scaleStep);
        for (int i = 0; i < trails.length; i++) {
            trails[i] = new Trail();
            if (world.windSnapshot != null) respawn(trails[i], world.windSnapshot, false);
        }
    }

    private void respawn(Trail trail, stableFluids.Snapshot field, boolean atInlet) {
        trail.count = 0;
        trail.length = trail.clock = trail.stalledSeconds = 0;
        float inletX = PApplet.cos(world.globalWindAngle), inletZ = PApplet.sin(world.globalWindAngle);
        for (int attempt = 0; attempt < 40; attempt++) {
            float x = field.minX + random.nextFloat() * field.nx * field.cell;
            float z = field.minZ + random.nextFloat() * field.nz * field.cell;
            if (atInlet) {
                if (random.nextFloat() * (Math.abs(inletX) + Math.abs(inletZ)) < Math.abs(inletX))
                    x = field.minX + (inletX >= 0 ? 0.08f : field.nx - 0.08f) * field.cell;
                else z = field.minZ + (inletZ >= 0 ? 0.08f : field.nz - 0.08f) * field.cell;
            }
            float y = world.surfaceHeightAt(x, z) - 45 * world.visualScale() - random.nextFloat() * 185 * world.visualScale();
            if (!field.contains(x, y, z) || field.isSolid(x, y, z)) continue;
            field.sample(x, y, z, wind);
            if (wind.magSq() < minimumSpeed*minimumSpeed) continue;
            trail.position.set(x, y, z);
            trail.append(wind.mag());
            return;
        }
    }

    void update(float seconds) {
        stableFluids.Snapshot field = world.windSnapshot;
        if (field == null || seconds <= 0) return;
        // Each head moves with the live field. The finite history follows it:
        // old positions expire instead of leaving stationary inlet streamlines.
        for (Trail trail : trails) {
            if (trail.count == 0) { respawn(trail, field, true); continue; }
            float remaining = Math.min(seconds, 0.1f);
            while (remaining > 0.000001f) {
                float dt = Math.min(remaining, 1f / 60);
                remaining -= dt;
                PVector point = trail.position;
                field.sample(point.x, point.y, point.z, wind);
                trail.stalledSeconds = wind.magSq() < minimumSpeed*minimumSpeed ? trail.stalledSeconds + dt : 0;
                if (trail.stalledSeconds > 0.35f) { respawn(trail, field, true); break; }
                float mx = point.x + wind.x * dt / 2, my = point.y + wind.y * dt / 2, mz = point.z + wind.z * dt / 2;
                if (!field.contains(mx, my, mz) || field.isSolid(mx, my, mz)) {
                    respawn(trail, field, true); break;
                }
                field.sample(mx, my, mz, middleWind);
                float dx = middleWind.x * dt, dy = middleWind.y * dt, dz = middleWind.z * dt;
                int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)))
                    / (field.cell * 0.15f)));
                boolean blocked = false;
                for (int step = 1; step <= steps; step++) {
                    float t = (float) step / steps;
                    float x = point.x + dx * t, y = point.y + dy * t, z = point.z + dz * t;
                    if (!field.contains(x, y, z) || field.isSolid(x, y, z)) { blocked = true; break; }
                }
                if (blocked) { respawn(trail, field, true); break; }
                point.add(dx, dy, dz);
                trail.clock += dt;
                if (trail.clock + 0.000001f >= sampleSeconds) {
                    trail.clock -= sampleSeconds;
                    field.sample(point.x, point.y, point.z, wind);
                    trail.append(wind.mag());
                }
            }
        }
    }

    boolean visible(Trail trail) {
        float cell = world.windSnapshot == null ? world.scale : world.windSnapshot.cell;
        float minimumLength = world.city == null ? Math.max(70*world.visualScale(),cell*2.5f)
            : Math.max(cell*0.8f,Math.min(cell*2.5f,world.inletSpeed()*5));
        if (trail.count < 6 || trail.length < minimumLength) return false;
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int n = 0; n < trail.count; n++) {
            int i = trail.point(n);
            minX = Math.min(minX, trail.x[i]); maxX = Math.max(maxX, trail.x[i]);
            minY = Math.min(minY, trail.y[i]); maxY = Math.max(maxY, trail.y[i]);
            minZ = Math.min(minZ, trail.z[i]); maxZ = Math.max(maxZ, trail.z[i]);
        }
        // A tightly coiled/stagnant trace must not become another tiny "hair".
        return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) >= cell * (world.city == null ? 1.5f : 0.6f);
    }

    private static float distance(float x, float y, float z, float px, float py, float pz) {
        float dx = x - px, dy = y - py, dz = z - pz;
        return PApplet.sqrt(dx * dx + dy * dy + dz * dz);
    }

    boolean prepareGroundColors() {
        stableFluids.Snapshot field = world.windSnapshot;
        if (field == null) return false;
        if (groundField == field) return true;
        // Sample a consistent height above the terrain, not inside the solid
        // ground itself. Keep boundary vertices just inside the fluid domain.
        float clearance = field.cell * 1.5f;
        float epsilon = field.cell * 0.001f;
        for (int x = 0; x < world.cols; x++) {
            float wx = PApplet.constrain(x * world.scale - world.w / 2f,
                field.minX + epsilon, field.minX + field.nx * field.cell - epsilon);
            for (int z = 0; z < world.rows; z++) {
                float wz = PApplet.constrain(z * world.scale - world.h / 2f,
                    field.minZ + epsilon, field.minZ + field.nz * field.cell - epsilon);
                float y = world.surfaceHeightAt(wx, wz) - clearance;
                field.sample(wx, y, wz, wind);
                groundColors[x][z] = speedColor(wind.mag(), maxSpeed);
            }
        }
        groundField = field;
        return true;
    }

    int groundColor(int x, int z) { return groundColors[x][z]; }

    static int speedColor(float speed, float maximum) {
        float position = PApplet.constrain(speed / maximum, 0, 1) * (PALETTE.length - 1);
        int index = Math.min((int) position, PALETTE.length - 2);
        float t = position - index;
        int from = PALETTE[index], to = PALETTE[index + 1];
        int r = Math.round(((from >> 16) & 255) * (1 - t) + ((to >> 16) & 255) * t);
        int g = Math.round(((from >> 8) & 255) * (1 - t) + ((to >> 8) & 255) * t);
        int b = Math.round((from & 255) * (1 - t) + (to & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    void draw(PApplet app, float cameraX, float cameraY, float cameraZ) {
        app.pushStyle();
        app.noLights();
        app.noStroke();
        // Retain terrain/tree occlusion, but let translucent ribbons overlap.
        app.hint(PApplet.DISABLE_DEPTH_MASK);
        for (Trail trail : trails) {
            if (!visible(trail)) continue;
            app.beginShape(PApplet.TRIANGLE_STRIP);
            for (int n = 0; n < trail.count; n++) {
                int i = trail.point(n);
                int before = trail.point(Math.max(0, n - 1)), after = trail.point(Math.min(trail.count - 1, n + 1));
                float tx = trail.x[after] - trail.x[before];
                float ty = trail.y[after] - trail.y[before];
                float tz = trail.z[after] - trail.z[before];
                float vx = cameraX - trail.x[i], vy = cameraY - trail.y[i], vz = cameraZ - trail.z[i];
                float sideX = ty * vz - tz * vy, sideY = tz * vx - tx * vz, sideZ = tx * vy - ty * vx;
                float norm = PApplet.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
                if (norm < 0.0001f) { sideX = 1; sideY = 0; sideZ = 0; norm = 1; }
                float taper = Math.min(1, Math.min((n + 1) / 8f, (trail.count - n) / 3f));
                float halfWidth = 2.8f * world.visualScale() * taper / norm;
                float age = (float) n / (trail.count - 1);
                app.fill(speedColor(trail.speed[i], maxSpeed), 35 + age * 205);
                app.vertex(trail.x[i] - sideX * halfWidth, trail.y[i] - sideY * halfWidth, trail.z[i] - sideZ * halfWidth);
                app.vertex(trail.x[i] + sideX * halfWidth, trail.y[i] + sideY * halfWidth, trail.z[i] + sideZ * halfWidth);
            }
            app.endShape();
        }
        app.hint(PApplet.ENABLE_DEPTH_MASK);
        app.popStyle();
    }

    void drawLegend(PApplet app) {
        float left = app.width - 204, top = app.height - 56, width = 188;
        app.noStroke();
        app.fill(0, 175);
        app.rect(left - 8, top - 4, width + 16, 55, 4);
        app.textSize(10);
        app.textAlign(PApplet.LEFT, PApplet.BASELINE);
        app.fill(225);
        app.text(world.city == null ? "Wind speed (units/s)" : "Wind speed (m/s, simulated)", left, top + 10);
        for (int pixel = 0; pixel < (int) width; pixel++) {
            app.fill(speedColor(pixel / (width - 1) * maxSpeed, maxSpeed));
            app.rect(left + pixel, top + 17, 1, 7);
        }
        app.fill(210);
        app.text("0", left, top + 39);
        app.textAlign(PApplet.CENTER, PApplet.BASELINE);
        app.text(Math.round(maxSpeed / 2), left + width / 2, top + 39);
        app.textAlign(PApplet.RIGHT, PApplet.BASELINE);
        app.text(Math.round(maxSpeed) + "+", left + width, top + 39);
    }

    boolean overLegend(float x, float y, int width, int height) {
        return x >= width - 212 && y >= height - 60;
    }
}
