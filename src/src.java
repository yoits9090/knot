import processing.core.PApplet;
import processing.core.PMatrix3D;
import processing.core.PVector;
import processing.opengl.PGraphicsOpenGL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

// Rendering and application entry point. Map and simulation state live in map.java.
public class src extends PApplet {

    map world;
    navigation navigationGrid;
    windVisualization windView;
    boolean showWind;
    int lastWindMillis;
    static final int HUD_HEIGHT = 60;
    List<int[]> path = Collections.emptyList();
    int[] start, goal;
    PMatrix3D inverseViewProjection;
    final ExecutorService pathWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "pathfinding");
        thread.setDaemon(true);
        return thread;
    });
    Future<Selection> pendingSelection;

    static class Selection {
        final int[] from, selected;
        final List<int[]> route;
        final String error;
        Selection(int[] from, int[] selected, List<int[]> route, String error) {
            this.from = from;
            this.selected = selected;
            this.route = route;
            this.error = error;
        }
    }

    boolean orbit = true;
    float cameraAngle;
    String pathStatus = "Click terrain to place the start.";

    public void settings() {
        size(800, 600, P3D);
        smooth(8);
    }

    public void setup() {
        noStroke();
        fill(204);
        world = new map(this);
        navigationGrid = new navigation(world);
        windView = new windVisualization(world);
        lastWindMillis = millis();
        surface.setTitle("Terrain | A* and Wind");
    }

    public void draw() {
        finishSelection();
        world.update(frameCount);
        int now = millis();
        float seconds = constrain((now - lastWindMillis) / 1000f, 0, 0.05f);
        lastWindMillis = now;
        if (showWind) windView.update(seconds);

        background(10);
        lights();
        directionalLight(200, 200, 200, 0.3f, -1, -0.4f);
        ambientLight(40, 40, 60);

        // View the centered terrain from above; negative Y is up.
        if (orbit) cameraAngle += 0.002f;
        float camX = 1400 * cos(cameraAngle);
        float camZ = 1400 * sin(cameraAngle);
        camera(camX, -1000, camZ, 0, 0, 0, 0, 1, 0);

        // Cache the actual rendered view before switching to the HUD camera.
        PGraphicsOpenGL renderer = (PGraphicsOpenGL) g;
        inverseViewProjection = renderer.projection.get();
        inverseViewProjection.apply(renderer.modelview);
        if (!inverseViewProjection.invert()) inverseViewProjection = null;

        drawTerrain();
        drawTrees();
        drawLeaves();
        if (showWind) windView.draw(this);
        drawPath();
        drawPathHud();
    }

    public void mousePressed(processing.event.MouseEvent event) {
        if (event.getButton() != LEFT) return;
        // HUD interactions must never place a path endpoint behind the panel.
        if (event.getY() < HUD_HEIGHT || inverseViewProjection == null) return;
        if (pendingSelection != null) return;
        PVector near = unproject(event.getX(), event.getY(), -1);
        PVector far = unproject(event.getX(), event.getY(), 1);
        PVector direction = PVector.sub(far, near).normalize();
        final int[] from = goal == null ? start : null;
        pathStatus = from == null ? "Picking start..." : "Finding route...";
        pendingSelection = pathWorker.submit(() -> {
            PVector hit = navigationGrid.raycast(near, direction);
            if (hit == null)
                return new Selection(from, null, null, "No terrain hit. Click inside the map.");
            int[] selected = navigationGrid.endpoint(hit);
            if (selected == null)
                return new Selection(from, null, null, "No free space above that point. Try another spot.");
            List<int[]> route = from == null ? Collections.emptyList()
                : astar.findPath(navigationGrid.blocked,
                    from[0], from[1], from[2], selected[0], selected[1], selected[2]);
            return new Selection(from, selected, route, null);
        });
    }

    void finishSelection() {
        if (pendingSelection == null || !pendingSelection.isDone()) return;
        Future<Selection> completed = pendingSelection;
        pendingSelection = null;
        try {
            Selection result = completed.get();
            if (result.error != null) {
                pathStatus = result.error;
            } else if (result.from == null) {
                start = result.selected;
                goal = null;
                path = Collections.emptyList();
                pathStatus = "Start placed. Click terrain to place the goal.";
            } else {
                goal = result.selected;
                path = result.route;
                pathStatus = path.isEmpty() ? "No route found. Click to choose a new start."
                    : "Route complete: " + (path.size() - 1) + " steps. Click to start a new route.";
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            pathStatus = "Selection interrupted. Press R to reset.";
        } catch (ExecutionException exception) {
            exception.getCause().printStackTrace();
            pathStatus = "Selection failed. Press R to reset.";
        }
    }

    PVector unproject(float screenX, float screenY, float clipZ) {
        float[] point = inverseViewProjection.mult(new float[]{
            2 * screenX / width - 1, 1 - 2 * screenY / height, clipZ, 1
        }, new float[4]);
        return new PVector(point[0] / point[3], point[1] / point[3], point[2] / point[3]);
    }

    public void keyPressed() {
        if (key == ' ') orbit = !orbit;
        if (key == 'c' || key == 'C') showWind = !showWind;
        if (key == 'r' || key == 'R') {
            if (pendingSelection != null) pendingSelection.cancel(true);
            pendingSelection = null;
            start = goal = null;
            path = Collections.emptyList();
            pathStatus = "Click terrain to place the start.";
        }
    }

    void drawPath() {
        pushStyle();
        noLights();
        noFill();
        stroke(40, 255, 90);
        strokeWeight(4);
        if (path.size() > 1) {
            beginShape();
            for (int[] node : path) {
                PVector point = navigationGrid.position(node);
                vertex(point.x, point.y, point.z);
            }
            endShape();
        }
        drawEndpoint(start, color(50, 180, 255));
        drawEndpoint(goal, color(40, 255, 90));
        popStyle();
    }

    void drawEndpoint(int[] node, int markerColor) {
        if (node == null) return;
        PVector point = navigationGrid.position(node);
        pushMatrix();
        translate(point.x, point.y, point.z);
        noStroke();
        fill(markerColor);
        sphere(7);
        popMatrix();
    }

    void drawPathHud() {
        hint(DISABLE_DEPTH_TEST);
        pushStyle();
        camera();
        noLights();
        noStroke();
        fill(0, 210);
        rect(0, 0, width, HUD_HEIGHT);
        fill(255);
        textSize(13);
        text(pathStatus, 14, 22);
        fill(190, 210, 220);
        textSize(11);
        text("Blue: start   Green: route   Space: "
            + (orbit ? "pause" : "resume") + " camera   R: reset   C: wind "
            + (showWind ? "on" : "off"), 14, 46);
        drawWindDirection();
        popStyle();
        hint(ENABLE_DEPTH_TEST);
    }

    void drawWindDirection() {
        // A compact, fixed bird's-eye arrow: +X is right and +Z is down.
        float centerX = width - 34, centerY = HUD_HEIGHT / 2f;
        float dx = cos(world.globalWindAngle), dz = sin(world.globalWindAngle);
        float tipX = centerX + dx * 17, tipY = centerY + dz * 17;
        stroke(90, 235, 255);
        strokeWeight(2);
        line(centerX - dx * 17, centerY - dz * 17, tipX, tipY);
        line(tipX, tipY, tipX - dx * 8 - dz * 4, tipY - dz * 8 + dx * 4);
        line(tipX, tipY, tipX - dx * 8 + dz * 4, tipY - dz * 8 - dx * 4);
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

    public void dispose() {
        pathWorker.shutdownNow();
        super.dispose();
    }

    public static void main(String[] args) {
        PApplet.main(src.class.getName());
    }
}
