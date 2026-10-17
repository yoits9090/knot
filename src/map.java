/*
 Authour: Abas
 Date: 10/17/2026
 desc: Generate a fixed map at startup using Perlin noise for height and random tree placement.
 Own the map data, wind simulation, tree sway, and falling leaf physics.
 Future fluid dynamics simulation: https://www.dgp.toronto.edu/public_user/stam/reality/Research/pdf/ns.pdf
 
 */
import processing.core.PApplet;

public class map {

    // Processing supplies noise and random helpers; rendering lives in src.java.
    private final PApplet processing;

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
        float swayPhase;     // random phase for wind sway
        float swayAmp;       // sway amplitude
        float swayX, swayZ;  // current canopy offset from wind
        int canopyColor;
    }

    Tree[] trees;
    int treeCount = (int)(Math.random() * 100) + 150; // Random number between 150 and 250

    // Wind field (very simplified, placeholder for fluid simulation)
    float globalWindAngle = 0.4f; // radians, direction of wind in XZ plane
    float globalWindStrength = 0.4f;

    // Falling leaves
    class Leaf {
        float x, y, z;
        float vy;
        float swayPhase;
        boolean active;
    }

    Leaf[] leaves;
    int maxLeaves = 200;

    public map(PApplet processing) {
        this.processing = processing;

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
            trees[i].swayPhase = processing.random(PApplet.TWO_PI);
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

    public void update(int frameCount) {
        updateWind(frameCount);
        updateTrees(frameCount);
        updateLeaves(frameCount);
    }

    // Replace this placeholder wind field with a CFD solver here.
    void updateWind(int frameCount) {
        globalWindAngle += 0.0015f * PApplet.sin(frameCount * 0.01f);
        globalWindStrength = 0.3f + 0.2f * processing.noise(frameCount * 0.01f);
    }

    // Update tree sway and occasionally shed leaves, independently of drawing.
    void updateTrees(int frameCount) {
        for (Tree t : trees) {
            float groundY = groundHeightAt(t.x, t.z);

            // Wind sway
            float windFactor = globalWindStrength * 0.6f;
            float sway = PApplet.sin(frameCount * 0.03f + t.swayPhase) * t.swayAmp * windFactor;
            t.swayX = PApplet.cos(globalWindAngle) * sway * 80;
            t.swayZ = PApplet.sin(globalWindAngle) * sway * 80;

            // Randomly shed a leaf depending on wind
            if (processing.random(1) < 0.002f * (0.5f + globalWindStrength)) {
                spawnLeaf(t.x + t.swayX, groundY - t.baseHeight + processing.random(-5, 5),
                          t.z + t.swayZ);
            }
        }
    }

    float groundHeightAt(float x, float z) {
        int gx = PApplet.constrain(PApplet.round((x + w / 2f) / scale), 0, cols - 1);
        int gz = PApplet.constrain(PApplet.round((z + h / 2f) / scale), 0, rows - 1);
        return terrain[gx][gz];
    }

    void spawnLeaf(float x, float y, float z) {
        for (Leaf leaf : leaves) {
            if (!leaf.active) {
                leaf.active = true;
                leaf.x = x;
                leaf.y = y;
                leaf.z = z;
                leaf.vy = processing.random(0.3f, 1.0f);
                leaf.swayPhase = processing.random(PApplet.TWO_PI);
                break;
            }
        }
    }

    void updateLeaves(int frameCount) {
        for (Leaf leaf : leaves) {
            if (!leaf.active) continue;

            // Gravity
            leaf.y += leaf.vy;

            // Horizontal drift from wind
            float windX = PApplet.cos(globalWindAngle) * globalWindStrength * 0.6f;
            float windZ = PApplet.sin(globalWindAngle) * globalWindStrength * 0.6f;
            float localSway = PApplet.sin(frameCount * 0.05f + leaf.swayPhase) * 0.7f;
            leaf.x += windX + localSway * 0.4f;
            leaf.z += windZ + localSway * 0.4f;

            // Stop when reaching terrain
            float groundY = groundHeightAt(leaf.x, leaf.z);

            if (leaf.y > groundY + 2) {
                leaf.active = false;
            }
        }
    }
}
