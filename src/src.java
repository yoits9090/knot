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
    boolean torontoMode = true;
    TorontoAtlas atlas;
    WeatherData weather;
    Future<WeatherData.Observation> pendingWeather;
    Future<TorontoRoute> pendingRoute;
    Future<TorontoAtlas.Hit> pendingCityPick;
    PVector pickedPoint;
    String pickedLabel;
    final CameraRig cameraRig = new CameraRig();
    TorontoRoute cityRoute;
    SearchPlayback search;
    boolean showBacktest;
    boolean preserveSearch, cameraInitialized;
    float localCenterX, localCenterZ;
    long lastWindowRequest;
    long nextWeatherRefresh;
    final ExecutorService weatherWorker = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "weather-source"); t.setDaemon(true); return t;
    });
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
    static final int HUD_HEIGHT = 82;
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

    boolean orbit = false;
    float cameraAngle;
    String pathStatus = "Click terrain to place the start.";

    public void settings() {
        size(1200, 800, P3D);
        pixelDensity(1);
        smooth(4);
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
        finishCityPick();
        finishWeather();
        finishRandomRoute();
        if(weather != null && cityMode && pendingWeather == null && System.currentTimeMillis() > nextWeatherRefresh
                && !weather.status.equals("Historical observation")) {
            pendingWeather = weatherWorker.submit(WeatherData::fetchLatest);
            nextWeatherRefresh = System.currentTimeMillis()+900_000;
        }
        int now = millis();
        float seconds = constrain((now - lastWindMillis) / 1000f, 0, 0.05f);
        lastWindMillis = now;
        world.update(frameCount, seconds);
        windView.update(seconds);
        cameraRig.update(seconds);
        if (!focused) cameraRig.held.clear();
        if (search != null) {
            if (!search.done) search.update(seconds);
            pathStatus = "A*: " + search.expanded.size() + " expanded, " + search.open.size() + " queued | 1 expansion / 0.05 s";
            if (search.done) {
                path = search.path;
                pathStatus = path.isEmpty() ? search.error : "Route complete: " + (path.size()-1) + " steps | P: another random route";
            }
        }
        if (atlas != null && cityMode && pendingMap == null && -cameraRig.y < 3000
                && System.currentTimeMillis() - lastWindowRequest > 3000) {
            PVector focus = cameraRig.focus();
            float targetX=constrain(focus.x,atlas.minX,atlas.maxX),targetZ=constrain(focus.z,atlas.minZ,atlas.maxZ);
            if (dist(targetX,targetZ,localCenterX,localCenterZ) > 230) {
                localCenterX = targetX;
                localCenterZ = targetZ;
                preserveSearch = true; regenerateMap();
            }
        }

        background(10);
        lights();
        directionalLight(200, 200, 200, 0.3f, -1, -0.4f);
        ambientLight(40, 40, 60);

        cameraRig.apply(this);
        float camX = cameraRig.x - (atlas != null && world.city != null ? world.city.centerX : 0);
        float camY = cameraRig.y;
        float camZ = cameraRig.z - (atlas != null && world.city != null ? world.city.centerZ : 0);

        // Cache the actual rendered view before switching to the HUD camera.
        PGraphicsOpenGL renderer = (PGraphicsOpenGL) g;
        inverseViewProjection = renderer.projection.get();
        inverseViewProjection.apply(renderer.modelview);
        if (!inverseViewProjection.invert()) inverseViewProjection = null;

        boolean citywide = atlas != null && world.city != null;
        if (citywide) {
            PVector focus = cameraRig.focus();
            atlas.draw(this,focus.x,focus.z,-cameraRig.y);
            pushMatrix(); translate(world.city.centerX,0,world.city.centerZ);
        }
        if (!citywide || showWind) drawTerrain();
        if (citywide && showWind) world.city.drawRoads(this,true);
        if (world.city != null && !citywide) {
            world.city.drawRoads(this, showWind);
            lights(); ambientLight(80,80,90);
            world.city.drawBuildings(this);
        }
        drawTrees(); drawLeaves();
        if (showWind) windView.draw(this,camX,camY,camZ);
        if (citywide) popMatrix();
        drawPath();
        drawPathHud();
    }

    public void mousePressed(processing.event.MouseEvent event) {
        if (event.getButton() != LEFT || world == null || pendingMap != null && !(atlas != null && world.city != null)) return;
        if (world.city != null && event.getX() < width - 220 && event.getY() >= height - 22) {
            link(atlas == null ? "https://www.openstreetmap.org/copyright" : "https://open.toronto.ca/open-data-license/");
            return;
        }
        // HUD interactions must never place a path endpoint behind the panel.
        if (event.getY() < HUD_HEIGHT || inverseViewProjection == null) return;
        if (showWind && windView.overLegend(event.getX(), event.getY(), width, height)) return;
        if (pendingSelection != null) return;
        PVector near = unproject(event.getX(), event.getY(), -1);
        PVector far = unproject(event.getX(), event.getY(), 1);
        PVector direction = PVector.sub(far, near).normalize();
        if (atlas != null && world.city != null) {
            if(pendingCityPick != null || pendingRoute != null)return;
            final TorontoAtlas source=atlas;
            if(Boolean.getBoolean("mathhack.debugPicking"))System.out.printf("Pick screen=%d,%d mouse=%d,%d near=%s far=%s%n",event.getX(),event.getY(),mouseX,mouseY,near,far);
            pathStatus="Picking surface...";
            pendingCityPick=pathWorker.submit(() -> source.raycast(near,direction));
            return;
        }
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
            List<int[]> route = Collections.emptyList();
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
                goal = null; search = null; cityRoute = null;
                path = Collections.emptyList();
                pathStatus = "Start placed. Click terrain to place the goal.";
            } else {
                goal = result.selected;
                path = Collections.emptyList();
                cityRoute = null;
                search = new SearchPlayback(navigationGrid.blocked.length, navigationGrid.blocked[0].length,
                    navigationGrid.blocked[0][0].length, (x,y,z) -> navigationGrid.blocked[x][y][z], start, goal);
                pathStatus = "A* search started | 0.05 s per expansion";
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
        int held = key == CODED ? keyCode : Character.toUpperCase(key);
        cameraRig.held.add(held);
        if (key == 'c' || key == 'C') showWind = !showWind;
        if (key == 'b' || key == 'B') showBacktest = !showBacktest;
        if ((key == 'm' || key == 'M') && pendingMap == null) {
            if(pendingRoute != null) pendingRoute.cancel(true);
            pendingRoute = null;
            if(pendingCityPick != null) pendingCityPick.cancel(true);
            pendingCityPick=null;
            cityMode = !cityMode; cameraInitialized = false; regenerateMap();
        }
        if ((key == 'p' || key == 'P') && atlas != null && cityMode && pendingRoute == null) {
            pathStatus = "Choosing random intersections and altitudes...";
            pendingRoute = pathWorker.submit(() -> TorontoRoute.random(atlas,new java.util.Random()));
        }
        if ((key == 'o' || key == 'O') && atlas != null && cityMode) cameraRig.overview(atlas);
        if ((key == 'l' || key == 'L') && pendingWeather == null) {
            if (weather != null) weather.status = "Requesting latest ECCC observation...";
            pendingWeather = weatherWorker.submit(WeatherData::fetchLatest);
        }
        if (key == 'r' || key == 'R') {
            if (weather != null && cityMode) weather.previous();
            preserveSearch = cityMode && search != null;
            regenerateMap();
        }
    }
    public void keyReleased() { cameraRig.held.remove(key == CODED ? keyCode : (int)Character.toUpperCase(key)); }
    public void mouseDragged(processing.event.MouseEvent event) {
        if (event.getButton() == RIGHT) cameraRig.drag(mouseX-pmouseX,mouseY-pmouseY);
    }
    public void mouseWheel(processing.event.MouseEvent event) {
        cameraRig.y = constrain(cameraRig.y * (float)Math.exp(event.getCount()*.12f),-60000,-8);
    }
    void finishCityPick() {
        if(pendingCityPick==null || !pendingCityPick.isDone())return;
        Future<TorontoAtlas.Hit> finished=pendingCityPick;pendingCityPick=null;
        try {
            TorontoAtlas.Hit hit=finished.get();
            if(hit==null){pathStatus="No surface hit. Click a roof, wall or ground.";return;}
            if(Boolean.getBoolean("mathhack.debugPicking"))System.out.println("Pick hit="+hit.label()+" "+hit.point);
            selectCityPoint(hit.endpoint(),hit.label());
        } catch(Exception e){pathStatus="Surface selection failed: "+e.getMessage();}
    }
    void selectCityPoint(PVector selected,String label) {
        if(atlas.segmentBlocked(selected,selected,TorontoRoute.CLEARANCE)) {
            pathStatus="No free space beside that surface. Try another point.";return;
        }
        if(pickedPoint==null || goal!=null || pickedIntersection!=null) {
            pickedPoint=selected.copy();pickedLabel=label;pickedIntersection=null;
            cityRoute=new TorontoRoute(atlas,selected,selected,label,label);
            start=cityRoute.start;goal=null;path=Collections.emptyList();search=null;
            pathStatus=label+" start at "+nf(-selected.y,0,1)+" m. Click a roof, wall or ground for the goal.";
        } else {
            TorontoRoute route=new TorontoRoute(atlas,pickedPoint,selected,pickedLabel,label);
            if(route.search.done&&route.search.error!=null){pathStatus=route.search.error;return;}
            cityRoute=route;start=route.start;goal=route.goal;search=route.search;path=Collections.emptyList();
            pathStatus="A* search started | 0.05 s per expansion";
        }
    }
    TorontoAtlas.Intersection pickedIntersection;
    void selectCityIntersection(TorontoAtlas.Intersection intersection) {
        pickedPoint=null; pickedLabel=null;
        if (pickedIntersection == null || goal != null) {
            pickedIntersection = intersection;
            cityRoute = new TorontoRoute(atlas,intersection,intersection,50,50);
            start = cityRoute.start; goal = null; path = Collections.emptyList(); search = null;
            if(cityRoute.search.done && cityRoute.search.error != null) {
                pathStatus="Start blocked at 50 m: choose another intersection.";
                start=null;pickedIntersection=null;cityRoute=null;
            } else pathStatus = "Start: " + intersection.name + " | click another intersection";
        } else {
            cityRoute = new TorontoRoute(atlas,pickedIntersection,intersection,50,70);
            start = cityRoute.start; goal = cityRoute.goal; search = cityRoute.search; path = Collections.emptyList();
            if(search.done && search.error != null) {
                goal=null;search=null;
                pathStatus="Goal blocked at 70 m: choose another intersection.";
            } else pathStatus = "A* search started | 0.05 s per expansion";
        }
    }
    void finishRandomRoute() {
        if(pendingRoute == null || !pendingRoute.isDone()) return;
        try {
            cityRoute = pendingRoute.get(); search = cityRoute.search;
            start = cityRoute.start; goal = cityRoute.goal; path = Collections.emptyList();
            pickedIntersection = cityRoute.from; pickedPoint=null; pickedLabel=null;
            cameraRig.route(cityRoute.from.x,cityRoute.from.z,cityRoute.to.x,cityRoute.to.z);
            pathStatus = "Random route | " + cityRoute.fromAltitude + "m to " + cityRoute.toAltitude + "m";
            if(pendingMap == null) {
                localCenterX = cameraRig.x; localCenterZ = cameraRig.z;
                preserveSearch = true; regenerateMap();
            }
        } catch(Exception e) { pathStatus = "Random route failed: " + e.getMessage(); }
        pendingRoute = null;
    }
    void finishWeather() {
        if(pendingWeather == null || !pendingWeather.isDone()) return;
        try {
            WeatherData.Observation observation = pendingWeather.get();
            if (weather != null) {
                weather.current = observation;
                weather.status = observation.stale() ? "Latest available (delayed)" : "Latest available";
                if(cityMode && pendingMap == null) { preserveSearch = cityMode && search != null; regenerateMap(); }
            }
        } catch(Exception e) { if(weather != null) weather.status = "ECCC unavailable; retaining dated cached observation"; }
        pendingWeather = null;
        nextWeatherRefresh = System.currentTimeMillis()+900_000;
    }

    void regenerateMap() {
        if (pendingMap != null) return;
        final boolean keepRoute = preserveSearch;
        preserveSearch = false;
        if (pendingSelection != null) pendingSelection.cancel(true);
        pendingSelection = null;
        if (!keepRoute) {
            start = goal = null; path = Collections.emptyList(); search = null; cityRoute = null; pickedIntersection = null; pickedPoint=null; pickedLabel=null;
            if(pendingCityPick!=null)pendingCityPick.cancel(true);
            pendingCityPick=null;
        }
        inverseViewProjection = null;
        noiseSeed(ThreadLocalRandom.current().nextLong());
        final float nextWindAngle = world == null ? random(TWO_PI)
            : (world.globalWindAngle + random(QUARTER_PI, TWO_PI - QUARTER_PI)) % TWO_PI;
        final boolean loadCity = cityMode;
        if (!keepRoute) pathStatus = loadCity ? "Loading Toronto geometry and wind..." : "Generating forest and wind...";
        lastWindowRequest = System.currentTimeMillis();
        final float centerX = localCenterX, centerZ = localCenterZ;
        final WeatherData.Observation observation = weather == null ? null : weather.current;
        pendingMap = mapWorker.submit(() -> {
            map next = null;
            try {
                CityScene scene = null;
                if (loadCity && torontoMode) {
                    if (atlas == null) atlas = TorontoAtlas.load();
                    if (weather == null) weather = new WeatherData();
                    scene = CityScene.window(atlas,centerX,centerZ,640);
                } else if (loadCity) {
                    if (cachedCity == null) cachedCity = CityScene.load();
                    scene = cachedCity;
                }
                next = new map(this,scene);
                WeatherData.Observation forcing = observation != null ? observation : weather == null ? null : weather.current;
                next.globalWindAngle = loadCity && forcing != null ? forcing.angle() : nextWindAngle;
                if(loadCity && forcing != null) next.observedWindSpeed = forcing.speed;
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
            if(search == null && start == null) pathStatus = "Click a roof, wall or ground to place the start.";
            if(!cameraInitialized) {
                cameraRig.resetLocal(localCenterX,localCenterZ); cameraInitialized = true;
                if(atlas != null && cityMode && pendingWeather == null) {
                    pendingWeather = weatherWorker.submit(WeatherData::fetchLatest);
                    nextWeatherRefresh = System.currentTimeMillis()+900_000;
                }
            }
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
        drawSearch();
        if (path.size() > 1) {
            beginShape();
            for (int[] node : path) {
                PVector point = routePoint(node);
                vertex(point.x, point.y, point.z);
            }
            endShape();
        }
        drawEndpoint(start, color(50, 180, 255));
        drawEndpoint(goal, color(40, 255, 90));
        popStyle();
    }

    PVector routePoint(int[] node) { return cityRoute != null ? cityRoute.point(node) : navigationGrid.position(node); }
    void drawSearch() {
        if(search == null) return;
        strokeWeight(4); beginShape(POINTS);
        int drawn=0;
        for(SearchPlayback.Node node : search.nodes.values()) {
            if(drawn++ > 30000) break;
            if(node.closed) stroke(60,150,255,150); else stroke(255,180,35,180);
            PVector p = routePoint(node.cell()); vertex(p.x,p.y,p.z);
        }
        endShape();
        stroke(255,65,100,130);strokeWeight(3);beginShape(POINTS);
        for(int[] node : search.rejected){PVector p=routePoint(node);vertex(p.x,p.y,p.z);}endShape();
        if(!search.done){stroke(255,205,40);strokeWeight(3);noFill();beginShape();
            for(int[] node: search.candidate()){PVector p=routePoint(node);vertex(p.x,p.y,p.z);}endShape();}
        stroke(40,255,90);strokeWeight(4);
    }

    void drawEndpoint(int[] node, int markerColor) {
        if (node == null) return;
        PVector point = routePoint(node);
        pushMatrix();
        translate(point.x, point.y, point.z);
        noStroke();
        fill(markerColor);
        sphere(cityRoute == null ? 7 : 3);
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
        text((world.city == null ? "Forest | " : atlas == null ? "Bloor-Yonge | " : "Toronto | ") + pathStatus, 14, 20);
        fill(190, 210, 220);
        textSize(11);
        text("WASD: move   Q/E: down/up   ←/→: turn   ↑/↓: tilt   Right-drag: look   Shift: fast   P: random route   O: Toronto",14,39);
        text("C: wind   R: previous wind hour   L: latest   B: backtest   M: city/forest   Blue: explored   Gold: frontier   Pink: blocked",14,57);
        if(weather != null && cityMode) {
            textSize(10);fill(160,220,235);
            text("ECCC City Centre | " + weather.current.label() + " | " + weather.status,14,74);
        }
        drawWindDirection();
        if (showWind) windView.drawLegend(this);
        if (world.city != null) {
            textAlign(LEFT, BASELINE); textSize(10); fill(0, 190); noStroke();
            rect(8, height - 65, width - 228, 59, 4);
            fill(220);
            text(atlas == null ? "460 m | Flat ground | estimated OSM heights" : "Steeles–harbour | 428,184 buildings | 640 m CFD window | wind at 15 m AGL | flat ground", 14, height - 29);
            text(atlas == null ? "© OpenStreetMap contributors | ODbL | click for attribution" : "Contains information licensed under the Open Government Licence – Toronto", 14, height - 14);
            if(cityRoute != null) {
                fill(220);textSize(10);
                text(cityRoute.from.name + " @ " + (int)cityRoute.fromAltitude + "m → " + cityRoute.to.name + " @ " + (int)cityRoute.toAltitude + "m",14,height-51);
            }
        }
        if(showBacktest && weather != null) {
            fill(0,235);noStroke();rect(20,110,width-40,195,8);fill(230);textSize(12);
            String[] lines=weather.backtestLines();for(int i=0;i<lines.length;i++)text(lines[i],34,136+i*23);
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
        pathWorker.shutdownNow(); weatherWorker.shutdownNow();
        super.dispose();
    }

    public static void main(String[] args) {
        PApplet.main(src.class.getName());
    }
}
