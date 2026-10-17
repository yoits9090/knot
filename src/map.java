/*
 Authour: Abas
 Date: 10/17/2026
 desc: Generate a fixed map at startup using Perlin noise for height and random tree placement.
 Own the map data, wind simulation, tree sway, and falling leaf physics.
 Fluid dynamics reference: https://www.dgp.toronto.edu/public_user/stam/reality/Research/pdf/ns.pdf
 
 */
import processing.core.PApplet;
import processing.core.PVector;

public class map {

    // Processing supplies noise and random helpers; rendering lives in src.java.
    private final PApplet processing;
    final CityScene city;

    // Terrain parameters
    int cols, rows;
    int scale = 20;          // distance between vertices
    int w = 2000;            // width of terrain in world units
    int h = 2000;            // depth of terrain in world units
    float terrainHeight = 180;       // total elevation range; lower makes gentler hills
    float terrainNoiseScale = 0.04f; // smaller steps make smoother, broader slopes
    float[][] terrain;

    // Tree representation
    class Tree {
        float x, z;          // position on terrain grid (world coordinates)
        float baseHeight;    // base height of tree trunk
        float swayAmp;       // sway amplitude
        float swayX, swayZ;  // current canopy offset from wind
        float swayVelocityX, swayVelocityZ;
        int canopyColor;
    }

    Tree[] trees;
    int treeCount = (int)(Math.random() * 100) + 150; // Random number between 150 and 250

    // Incoming wind; local velocities are solved in the 3D fluid grid.
    float globalWindAngle = 0.4f; // radians, direction of wind in XZ plane
    float globalWindStrength = 0.4f;

    windSimulation wind;
    stableFluids.Snapshot windSnapshot;
    private final PVector sampledWind = new PVector();

    // Falling leaves
    class Leaf {
        float x, y, z;
        float vx, vy, vz;
        float swayPhase;
        boolean active;
    }

    Leaf[] leaves;
    int maxLeaves = 200;

    public map(PApplet processing) { this(processing, null); }

    public map(PApplet processing, CityScene city) {
        this.processing = processing;
        this.city = city;
        if (city != null) {
            w = h = (int) CityScene.SPAN;
            scale = 5;
            cols = w / scale + 1;
            rows = h / scale + 1;
            terrainHeight = 0;
            terrain = new float[cols][rows]; // Explicitly flat, not real elevation.
            trees = new Tree[0]; treeCount = 0;
            leaves = new Leaf[maxLeaves];
            for (int i = 0; i < maxLeaves; i++) leaves[i] = new Leaf();
            return;
        }

        cols = w / scale;
        rows = h / scale;
        terrain = new float[cols][rows];

        // Generate one fixed landscape at startup. Negative Y is higher ground.
        processing.noiseDetail(3, 0.45f);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                float n = processing.noise(x * terrainNoiseScale, y * terrainNoiseScale);
                terrain[x][y] = PApplet.map(n, 0, 1, terrainHeight / 2, -terrainHeight / 2);
            }
        }

        // Create random trees distributed over terrain
        trees = new Tree[treeCount];
        for (int i = 0; i < treeCount; i++) {
            trees[i] = new Tree();
            float gx = processing.random(cols - 1);
            float gz = processing.random(rows - 1);
            float wx = gx * scale - w / 2f;
            float wz = gz * scale - h / 2f;
            trees[i].x = wx;
            trees[i].z = wz;
            trees[i].baseHeight = processing.random(60, 130);
            trees[i].swayAmp = processing.random(0.06f, 0.16f);
            trees[i].canopyColor = processing.color(20, processing.random(90, 120), 20, 230);
        }

        // Initialize leaves
        leaves = new Leaf[maxLeaves];
        for (int i = 0; i < maxLeaves; i++) {
            leaves[i] = new Leaf();
            leaves[i].active = false;
        }
    }

    float airTop() { return city == null ? -terrainHeight / 2 - 260 : -city.tallest - 115; }
    float inletSpeed() { return globalWindStrength * (city == null ? 300 : 30); }
    float visualScale() { return city == null ? 1 : CityScene.SPAN / 1980f; }

    void startWind() {
        wind = new windSimulation(this);
        windSnapshot = wind.solver.latest;
    }

    void close() {
        if (wind != null) wind.close();
    }

    PVector sampleWind(float x, float y, float z, PVector result) {
        return windSnapshot == null ? result.set(0, 0, 0) : windSnapshot.sample(x, y, z, result);
    }

    public void update(int frameCount, float seconds) {
        if (wind != null) windSnapshot = wind.solver.latest;
        updateTrees(seconds);
        updateLeaves(seconds);
    }

    void updateTrees(float seconds) {
        for (Tree tree : trees) {
            float ground = groundHeightAt(tree.x, tree.z);
            float top = ground - tree.baseHeight;
            float radius = 24 + (windSnapshot == null ? scale * 2 : windSnapshot.cell * 1.8f);
            float vx = 0, vz = 0;
            // Sample ambient air around the canopy, not the zero-velocity solid.
            for (int side = 0; side < 4; side++) {
                float angle = side * PApplet.HALF_PI;
                sampleWind(tree.x + PApplet.cos(angle) * radius, top,
                    tree.z + PApplet.sin(angle) * radius, sampledWind);
                vx += sampledWind.x / 4;
                vz += sampledWind.z / 4;
            }
            float maximumSway = tree.swayAmp * 24;
            float targetX = vx / 140 * maximumSway, targetZ = vz / 140 * maximumSway;
            float targetLength = PApplet.sqrt(targetX * targetX + targetZ * targetZ);
            if (targetLength > maximumSway) {
                targetX *= maximumSway / targetLength;
                targetZ *= maximumSway / targetLength;
            }
            // Damped spring driven by the local fluid, not a sine-wave wind.
            tree.swayVelocityX += ((targetX - tree.swayX) * 25 - tree.swayVelocityX * 7) * seconds;
            tree.swayVelocityZ += ((targetZ - tree.swayZ) * 25 - tree.swayVelocityZ * 7) * seconds;
            tree.swayX += tree.swayVelocityX * seconds;
            tree.swayZ += tree.swayVelocityZ * seconds;
            float swayLength = PApplet.sqrt(tree.swayX * tree.swayX + tree.swayZ * tree.swayZ);
            if (swayLength > maximumSway) {
                tree.swayX *= maximumSway / swayLength;
                tree.swayZ *= maximumSway / swayLength;
                tree.swayVelocityX = tree.swayVelocityZ = 0;
            }
            float speed = PApplet.sqrt(vx * vx + vz * vz);
            if (processing.random(1) < seconds * (0.03f + speed * 0.0015f)) {
                float norm = Math.max(speed, 0.001f);
                spawnLeaf(tree.x + vx / norm * radius, top + processing.random(-5, 5),
                    tree.z + vz / norm * radius);
            }
        }
    }

    // Interpolate the exact diagonal used by drawTerrain's triangle strip.
    float surfaceHeightAt(float x, float z) {
        float gx = PApplet.constrain((x + w / 2f) / scale, 0, cols - 1);
        float gz = PApplet.constrain((z + h / 2f) / scale, 0, rows - 1);
        int i = Math.min((int) gx, cols - 2), k = Math.min((int) gz, rows - 2);
        float fx = gx - i, fz = gz - k;
        if (fx + fz <= 1)
            return terrain[i][k] + fx * (terrain[i + 1][k] - terrain[i][k])
                + fz * (terrain[i][k + 1] - terrain[i][k]);
        return terrain[i + 1][k + 1] + (1 - fx) * (terrain[i][k + 1] - terrain[i + 1][k + 1])
            + (1 - fz) * (terrain[i + 1][k] - terrain[i + 1][k + 1]);
    }

    float groundHeightAt(float x, float z) {
        int gx = PApplet.constrain(PApplet.round((x + w / 2f) / scale), 0, cols - 1);
        int gz = PApplet.constrain(PApplet.round((z + h / 2f) / scale), 0, rows - 1);
        return terrain[gx][gz];
    }

    void spawnLeaf(float x, float y, float z) {
        if (windSnapshot == null || !windSnapshot.contains(x, y, z) || windSnapshot.isSolid(x, y, z)) return;
        for (Leaf leaf : leaves) {
            if (leaf.active) continue;
            leaf.active = true;
            leaf.x = x; leaf.y = y; leaf.z = z;
            sampleWind(x, y, z, sampledWind);
            leaf.vx = sampledWind.x;
            leaf.vy = sampledWind.y + 10;
            leaf.vz = sampledWind.z;
            leaf.swayPhase = processing.random(PApplet.TWO_PI); // Visual orientation only.
            return;
        }
    }

    void updateLeaves(float seconds) {
        for (Leaf leaf : leaves) {
            if (!leaf.active) continue;
            sampleWind(leaf.x, leaf.y, leaf.z, sampledWind);
            float drag = 1 - PApplet.exp(-2.5f * seconds);
            leaf.vx += (sampledWind.x - leaf.vx) * drag;
            leaf.vy += 45 * seconds;
            leaf.vy += (sampledWind.y - leaf.vy) * drag;
            leaf.vz += (sampledWind.z - leaf.vz) * drag;
            float dx = leaf.vx * seconds, dy = leaf.vy * seconds, dz = leaf.vz * seconds;
            int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)))
                / (windSnapshot == null ? scale * 0.25f : windSnapshot.cell * 0.25f)));
            for (int step = 1; step <= steps; step++) {
                float t = (float) step / steps;
                float x = leaf.x + dx * t, y = leaf.y + dy * t, z = leaf.z + dz * t;
                if (windSnapshot == null || !windSnapshot.contains(x, y, z) || windSnapshot.isSolid(x, y, z)
                        || y >= surfaceHeightAt(x, z)) {
                    leaf.active = false;
                    break;
                }
            }
            if (leaf.active) { leaf.x += dx; leaf.y += dy; leaf.z += dz; }
        }
    }
}
