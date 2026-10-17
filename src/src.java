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
    boolean showWind = true;
    boolean cityMode = true;
    CityScene cachedCity;
    Future<PreparedMap> pendingMap;
    final ExecutorService mapWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "map-loader");
        thread.setDaemon(true);
        return thread;
    });

    static class PreparedMap {
        final map world;
        final navigation navigationGrid;
        PreparedMap(map world, navigation grid) { this.world = world; navigationGrid = grid; }
    }
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
        regenerateMap();
        surface.setTitle("Terrain | A* and Wind");
    }

    public void draw() {
        finishMap();
        if (world == null) {
            background(10); camera(); noLights(); fill(225); textSize(14);
            text(pathStatus, 20, 40);
            return;
        }
        finishSelection();
        int now = millis();
        float seconds = constrain((now - lastWindMillis) / 1000f, 0, 0.05f);
        lastWindMillis = now;
        world.update(frameCount, seconds);
        windView.update(seconds);

        background(10);
        lights();
        directionalLight(200, 200, 200, 0.3f, -1, -0.4f);
        ambientLight(40, 40, 60);

        // View the centered terrain from above; negative Y is up.
        if (orbit) cameraAngle += 0.002f;
        float viewSpan = world.city == null ? world.w : max(world.w, world.city.tallest * 2);
        float radius = viewSpan * (world.city == null ? 0.7f : 0.85f);
        float camY = -viewSpan * (world.city == null ? 0.5f : 0.8f);
        float focusY = world.city == null ? 0 : -world.city.tallest * 0.3f;
        float camX = radius * cos(cameraAngle);
        float camZ = radius * sin(cameraAngle);
        camera(camX, camY, camZ, 0, focusY, 0, 0, 1, 0);

        // Cache the actual rendered view before switching to the HUD camera.
        PGraphicsOpenGL renderer = (PGraphicsOpenGL) g;
        inverseViewProjection = renderer.projection.get();
        inverseViewProjection.apply(renderer.modelview);
        if (!inverseViewProjection.invert()) inverseViewProjection = null;

        drawTerrain();
        if (world.city != null) {
            world.city.drawRoads(this, showWind);
            lights();
            directionalLight(200, 200, 200, 0.3f, -1, -0.4f);
            ambientLight(80, 80, 90);
            world.city.drawBuildings(this);
        }
        drawTrees();
        drawLeaves();
        if (showWind) windView.draw(this, camX, camY, camZ);
        drawPath();
        drawPathHud();
    }

    public void mousePressed(processing.event.MouseEvent event) {
        if (event.getButton() != LEFT || world == null || pendingMap != null) return;
        if (world.city != null && event.getX() < width - 220 && event.getY() >= height - 22) {
            link("https://www.openstreetmap.org/copyright");
            return;
        }
        // HUD interactions must never place a path endpoint behind the panel.
        if (event.getY() < HUD_HEIGHT || inverseViewProjection == null) return;
        if (showWind && windView.overLegend(event.getX(), event.getY(), width, height)) return;
        if (pendingSelection != null) return;
        PVector near = unproject(event.getX(), event.getY(), -1);
        PVector far = unproject(event.getX(), event.getY(), 1);
        PVector direction = PVector.sub(far, near).normalize();
        final int[] from = goal == null ? start : null;
        final navigation grid = navigationGrid;
        pathStatus = from == null ? "Picking start..." : "Finding route...";
        pendingSelection = pathWorker.submit(() -> {
            PVector hit = grid.raycast(near, direction);
            if (hit == null)
                return new Selection(from, null, null, "No terrain hit. Click inside the map.");
            int[] selected = grid.endpoint(hit);
            if (selected == null)
                return new Selection(from, null, null, "No free space above that point. Try another spot.");
            List<int[]> route = from == null ? Collections.emptyList()
                : astar.findPath(grid.blocked,
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
            pathStatus = "Selection interrupted. Press R for a new map.";
        } catch (ExecutionException exception) {
            exception.getCause().printStackTrace();
            pathStatus = "Selection failed. Press R for a new map.";
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
        if ((key == 'm' || key == 'M') && pendingMap == null) {
            cityMode = !cityMode;
            regenerateMap();
        }
        if (key == 'r' || key == 'R') regenerateMap();
    }

    void regenerateMap() {
        if (pendingMap != null) return;
        if (pendingSelection != null) pendingSelection.cancel(true);
        pendingSelection = null;
        start = goal = null;
        path = Collections.emptyList();
        inverseViewProjection = null;
        noiseSeed(ThreadLocalRandom.current().nextLong());
        final float nextWindAngle = world == null ? random(TWO_PI)
            : (world.globalWindAngle + random(QUARTER_PI, TWO_PI - QUARTER_PI)) % TWO_PI;
        final boolean loadCity = cityMode;
        pathStatus = loadCity ? "Loading Bloor-Yonge and wind..." : "Generating forest and wind...";
        pendingMap = mapWorker.submit(() -> {
            map next = null;
            try {
                if (loadCity && cachedCity == null) cachedCity = CityScene.load();
                next = new map(this, loadCity ? cachedCity : null);
                next.globalWindAngle = nextWindAngle;
                navigation grid = new navigation(next);
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                next.startWind();
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                return new PreparedMap(next, grid);
            } catch (RuntimeException exception) {
                if (next != null) next.close();
                throw exception;
            }
        });
    }

    void finishMap() {
        if (pendingMap == null || !pendingMap.isDone()) return;
        Future<PreparedMap> completed = pendingMap;
        pendingMap = null;
        try {
            PreparedMap prepared = completed.get();
            if (world != null) world.close();
            world = prepared.world;
            navigationGrid = prepared.navigationGrid;
            windView = new windVisualization(world);
            inverseViewProjection = null;
            lastWindMillis = millis();
            pathStatus = "Click terrain to place the start.";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            pathStatus = "Map loading interrupted.";
        } catch (ExecutionException | CancellationException exception) {
            exception.printStackTrace();
            pathStatus = "Map failed to load. M: switch mode, R: retry.";
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
        text((world.city == null ? "Forest | " : "Bloor-Yonge | ") + pathStatus, 14, 22);
        fill(190, 210, 220);
        textSize(11);
        text("Blue: start   Green: route   Space: "
            + (orbit ? "pause" : "resume") + " camera   R: new wind/map   M: city/forest   C: wind "
            + (showWind ? "on" : "off"), 14, 46);
        drawWindDirection();
        if (showWind) windView.drawLegend(this);
        if (world.city != null) {
            textAlign(LEFT, BASELINE); textSize(10); fill(0, 190); noStroke();
            rect(8, height - 43, width - 228, 37, 4);
            fill(220);
            text("460 m | Flat ground | " + world.city.levelHeights + " floor estimates, "
                + world.city.defaultHeights + " default heights", 14, height - 29);
            text("© OpenStreetMap contributors | ODbL | click for attribution", 14, height - 14);
        }
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
        boolean windHeatmap = showWind && windView.prepareGroundColors();
        // Keep numerical heatmap colours identical to the unlit speed legend.
        if (windHeatmap) noLights();
        noStroke();
        int baseGreen = world.city == null ? color(140, 220, 140) : color(170, 177, 183);  // lighter mid green
        int darkGreen = world.city == null ? color(10, 40, 10) : color(145, 154, 162);     // darker green for low areas

        for (int y = 0; y < world.rows - 1; y++) {
            beginShape(TRIANGLE_STRIP);
            for (int x = 0; x < world.cols; x++) {
                float h1 = world.terrain[x][y];
                float h2 = world.terrain[x][y + 1];

                // 0 at highest, 1 at lowest (negative Y is up).
                float t1 = world.terrainHeight > 0 ? map(h1, -world.terrainHeight / 2, world.terrainHeight / 2, 0, 1) : 0.5f;
                float t2 = world.terrainHeight > 0 ? map(h2, -world.terrainHeight / 2, world.terrainHeight / 2, 0, 1) : 0.5f;

                // Lower ground -> darker green.
                int c1 = windHeatmap ? windView.groundColor(x, y) : lerpColor(baseGreen, darkGreen, t1);
                int c2 = windHeatmap ? windView.groundColor(x, y + 1) : lerpColor(baseGreen, darkGreen, t2);

                fill(c1);
                vertex(x * world.scale - world.w / 2f, h1, y * world.scale - world.h / 2f);

                fill(c2);
                vertex(x * world.scale - world.w / 2f, h2, (y + 1) * world.scale - world.h / 2f);
            }
            endShape();
        }
        if (windHeatmap) {
            lights();
            directionalLight(200, 200, 200, 0.3f, -1, -0.4f);
            ambientLight(40, 40, 60);
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
        if (pendingMap != null) {
            if (pendingMap.isDone() && !pendingMap.isCancelled()) {
                try { pendingMap.get().world.close(); } catch (Exception ignored) { }
            } else pendingMap.cancel(true);
        }
        mapWorker.shutdownNow();
        if (world != null) world.close();
        pathWorker.shutdownNow();
        super.dispose();
    }

    public static void main(String[] args) {
        PApplet.main(src.class.getName());
    }
}
