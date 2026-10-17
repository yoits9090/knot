import processing.core.PApplet;

// Rendering and application entry point. Map and simulation state live in map.java.
public class src extends PApplet {

    map world;

    public void settings() {
        size(800, 600, P3D);
        smooth(8);
    }

    public void setup() {
        noStroke();
        fill(204);
        world = new map(this);
    }

    public void draw() {
        world.update(frameCount);

        background(10);
        lights();
        directionalLight(200, 200, 200, 0.3f, -1, -0.4f);
        ambientLight(40, 40, 60);

        // View the centered terrain from above; negative Y is up.
        float camX = 1400 * cos(frameCount * 0.002f);
        float camZ = 1400 * sin(frameCount * 0.002f);
        camera(camX, -1000, camZ, 0, 0, 0, 0, 1, 0);

        drawTerrain();
        drawTrees();
        drawLeaves();
    }

    void drawTerrain() {
        noStroke();
        int baseGreen = color(140, 220, 140);  // lighter mid green
        int darkGreen = color(10, 40, 10);     // darker green for low areas

        for (int y = 0; y < world.rows - 1; y++) {
            beginShape(TRIANGLE_STRIP);
            for (int x = 0; x < world.cols; x++) {
                float h1 = world.terrain[x][y];
                float h2 = world.terrain[x][y + 1];

                // 0 at highest, 1 at lowest (negative Y is up).
                float t1 = map(h1, -world.terrainHeight / 2, world.terrainHeight / 2, 0, 1);
                float t2 = map(h2, -world.terrainHeight / 2, world.terrainHeight / 2, 0, 1);

                // Lower ground -> darker green.
                int c1 = lerpColor(baseGreen, darkGreen, t1);
                int c2 = lerpColor(baseGreen, darkGreen, t2);

                fill(c1);
                vertex(x * world.scale - world.w / 2f, h1, y * world.scale - world.h / 2f);

                fill(c2);
                vertex(x * world.scale - world.w / 2f, h2, (y + 1) * world.scale - world.h / 2f);
            }
            endShape();
        }
    }

    void drawTrees() {
        for (map.Tree t : world.trees) {
            float groundY = world.groundHeightAt(t.x, t.z);

            // Tree trunk
            pushMatrix();
            translate(t.x, groundY, t.z);
            stroke(60, 40, 20);
            strokeWeight(3);
            line(0, 0, 0, t.swayX, -t.baseHeight, t.swayZ);

            // Canopy (simple ellipsoid)
            pushMatrix();
            translate(t.swayX, -t.baseHeight, t.swayZ);
            noStroke();
            fill(t.canopyColor);
            sphere(18);
            popMatrix();
            popMatrix();
        }
        noStroke();
    }

    void drawLeaves() {
        noStroke();
        for (map.Leaf leaf : world.leaves) {
            if (!leaf.active) continue;
            pushMatrix();
            translate(leaf.x, leaf.y, leaf.z);
            rotateY(frameCount * 0.1f + leaf.swayPhase);
            fill(180 + random(-20, 20), 140 + random(-20, 20), 20, 240);
            beginShape(QUADS);
            float s = 3;
            vertex(-s, 0, -s);
            vertex(s, 0, -s);
            vertex(s, 0, s);
            vertex(-s, 0, s);
            endShape();
            popMatrix();
        }
    }

    public static void main(String[] args) {
        PApplet.main(src.class.getName());
    }
}
