import java.awt.geom.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import processing.core.*;

/** Cached OSM geometry in local metres: +X east, +Z south, negative Y up. */
public final class CityScene {
    static final float SPAN = 460;
    static final double METRES_PER_DEGREE = Math.PI * 6371008.8 / 180;
    final List<Building> buildings = new ArrayList<>();
    final List<Road> roads = new ArrayList<>();
    final double latitude, longitude;
    int taggedHeights, levelHeights, defaultHeights;
    float tallest;
    private PShape mesh;

    static final class Building {
        final String id, name, heightSource;
        final float height, bottom;
        final Area footprint;
        final List<float[]> rings;
        final Rectangle2D bounds;
        Building(String id, String name, String source, float height, float bottom, Area footprint) {
            this.id = id; this.name = name; heightSource = source;
            this.height = height; this.bottom = bottom; this.footprint = footprint;
            bounds = footprint.getBounds2D(); rings = rings(footprint);
        }
        boolean overlaps(float x0, float z0, float size) {
            return footprint.intersects(x0, z0, size, size);
        }
    }
    static final class Road {
        final String name;
        final float[] points;
        final float width;
        Road(String name, float[] points, float width) { this.name = name; this.points = points; this.width = width; }
    }

    static CityScene load() {
        Path path = Paths.get("data/bloor-yonge.osm");
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Missing cached OSM extract: " + path.toAbsolutePath());
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return new CityScene(factory.newDocumentBuilder().parse(path.toFile()));
        } catch (Exception exception) { throw new IllegalStateException("Cannot load Bloor-Yonge cache", exception); }
    }

    private CityScene(Document document) {
        Map<String, Element> nodes = elements(document, "node"), ways = elements(document, "way");
        Element origin = nodes.get("20964462");
        if (origin == null) throw new IllegalArgumentException("Bloor/Yonge intersection node is missing");
        latitude = Double.parseDouble(origin.getAttribute("lat"));
        longitude = Double.parseDouble(origin.getAttribute("lon"));
        Map<String, float[]> positions = new HashMap<>();
        for (Map.Entry<String, Element> entry : nodes.entrySet()) {
            Element node = entry.getValue();
            positions.put(entry.getKey(), project(Double.parseDouble(node.getAttribute("lat")),
                Double.parseDouble(node.getAttribute("lon"))));
        }
        Area domain = new Area(new Rectangle2D.Float(-SPAN / 2, -SPAN / 2, SPAN, SPAN));
        List<Building> outlines = new ArrayList<>(), parts = new ArrayList<>();
        Set<String> relationMembers = new HashSet<>();
        for (Element relation : elements(document, "relation").values()) {
            Map<String, String> tags = tags(relation);
            if (!"multipolygon".equals(tags.get("type"))) continue;
            Area shape = new Area();
            List<Area> holes = new ArrayList<>();
            NodeList members = relation.getElementsByTagName("member");
            for (int i = 0; i < members.getLength(); i++) {
                Element member = (Element) members.item(i);
                if (!"way".equals(member.getAttribute("type"))) continue;
                String ref = member.getAttribute("ref");
                Element way = ways.get(ref);
                if (way == null) throw new IllegalArgumentException("Missing building relation member " + ref);
                relationMembers.add(ref);
                Area ring = polygon(way, positions);
                if ("inner".equals(member.getAttribute("role"))) holes.add(ring);
                else shape.add(ring);
            }
            for (Area hole : holes) shape.subtract(hole);
            shape.intersect(domain);
            addBuilding("relation/" + relation.getAttribute("id"), tags, shape, outlines, parts);
        }
        for (Element way : ways.values()) {
            Map<String, String> tags = tags(way);
            String id = way.getAttribute("id");
            if (!relationMembers.contains(id) && (isBuilding(tags, "building") || isBuilding(tags, "building:part"))) {
                Area shape = polygon(way, positions);
                shape.intersect(domain);
                addBuilding("way/" + id, tags, shape, outlines, parts);
            }
            if (tags.containsKey("highway") && !"yes".equals(tags.get("tunnel"))) {
                float[] points = points(way, positions);
                float width = length(tags.get("width"), -1);
                if (width <= 0) width = Math.max(4, length(tags.get("lanes"), 2) * 3 + 2);
                roads.add(new Road(tags.getOrDefault("name", ""), points, width));
            }
        }
        // Building parts carry towers/setbacks. Preserve a separately tagged low
        // podium (e.g. Manulife), but never extrude the full tower outline again.
        for (Building outline : outlines) {
            float lowestGroundedPart = Float.POSITIVE_INFINITY;
            boolean hasParts = false;
            for (Building part : parts) {
                if (!outline.bounds.intersects(part.bounds)) continue;
                Area overlap = new Area(part.footprint);
                overlap.intersect(outline.footprint);
                if (area(overlap) < area(part.footprint) * 0.75) continue;
                hasParts = true;
                if (part.bottom < 0.5f) lowestGroundedPart = Math.min(lowestGroundedPart, part.height);
            }
            if (!hasParts || (!"default".equals(outline.heightSource) && outline.height <= lowestGroundedPart + 0.1f))
                buildings.add(outline);
        }
        buildings.addAll(parts);
        for (Building building : buildings) {
            tallest = Math.max(tallest, building.height);
            if ("height".equals(building.heightSource)) taggedHeights++;
            else if ("levels".equals(building.heightSource)) levelHeights++;
            else defaultHeights++;
        }
        if (buildings.isEmpty()) throw new IllegalArgumentException("No usable OSM buildings in the requested area");
    }

    float[] project(double lat, double lon) {
        return new float[]{(float) ((lon - longitude) * METRES_PER_DEGREE * Math.cos(Math.toRadians(latitude))),
            (float) ((latitude - lat) * METRES_PER_DEGREE)};
    }

    private static Map<String, Element> elements(Document document, String type) {
        Map<String, Element> result = new LinkedHashMap<>();
        NodeList nodes = document.getElementsByTagName(type);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i); result.put(e.getAttribute("id"), e);
        }
        return result;
    }
    private static Map<String, String> tags(Element e) {
        Map<String, String> tags = new HashMap<>();
        NodeList list = e.getElementsByTagName("tag");
        for (int i = 0; i < list.getLength(); i++) {
            Element tag = (Element) list.item(i); tags.put(tag.getAttribute("k"), tag.getAttribute("v"));
        }
        return tags;
    }
    private static boolean isBuilding(Map<String, String> tags, String key) {
        return tags.containsKey(key) && !"no".equals(tags.get(key));
    }
    static float length(String value, float fallback) {
        if (value == null) return fallback;
        try {
            String clean = value.trim().toLowerCase(Locale.ROOT);
            boolean feet = clean.endsWith("ft") || clean.endsWith("'");
            clean = clean.replaceAll("(?i)\\s*(metres?|meters?|m|ft|')$", "").trim();
            float result = Float.parseFloat(clean) * (feet ? 0.3048f : 1);
            return Float.isFinite(result) && result >= 0 ? result : fallback;
        } catch (NumberFormatException exception) { return fallback; }
    }
    private static void addBuilding(String id, Map<String, String> tags, Area area,
            List<Building> outlines, List<Building> parts) {
        if (area.isEmpty() || "underground".equals(tags.get("location"))) return;
        float height = length(tags.get("height"), -1);
        String source = "height";
        if (height <= 0) {
            float levels = length(tags.get("building:levels"), -1);
            height = levels > 0 ? levels * 3 + length(tags.get("roof:height"), 0) : 12;
            source = levels > 0 ? "levels" : "default";
        }
        float bottom = length(tags.get("min_height"), length(tags.get("building:min_level"), 0) * 3);
        if (bottom >= height) return;
        Building building = new Building(id, tags.getOrDefault("name", tags.getOrDefault("addr:street", "")),
            source, height, bottom, area);
        (isBuilding(tags, "building:part") ? parts : outlines).add(building);
    }
    private static float[] points(Element way, Map<String, float[]> nodes) {
        NodeList refs = way.getElementsByTagName("nd");
        float[] points = new float[refs.getLength() * 2];
        for (int i = 0; i < refs.getLength(); i++) {
            float[] point = nodes.get(((Element) refs.item(i)).getAttribute("ref"));
            if (point == null) throw new IllegalArgumentException("Missing cached geometry node");
            points[i * 2] = point[0]; points[i * 2 + 1] = point[1];
        }
        return points;
    }
    private static Area polygon(Element way, Map<String, float[]> nodes) {
        float[] points = points(way, nodes);
        if (points.length < 8 || points[0] != points[points.length - 2] || points[1] != points[points.length - 1])
            return new Area(); // Never invent a closing edge for an incomplete way.
        Path2D.Float path = path(points);
        return new Area(path);
    }
    private static Path2D.Float path(float[] points) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.closePath(); return path;
    }
    private static List<float[]> rings(Area area) {
        List<float[]> result = new ArrayList<>();
        List<Float> current = new ArrayList<>();
        double[] point = new double[6];
        for (PathIterator it = area.getPathIterator(null, 0.05); !it.isDone(); it.next()) {
            int type = it.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) current.clear();
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                current.add((float) point[0]); current.add((float) point[1]);
            } else if (type == PathIterator.SEG_CLOSE && current.size() >= 6) {
                float[] ring = new float[current.size()];
                for (int i = 0; i < ring.length; i++) ring[i] = current.get(i);
                result.add(ring);
            }
        }
        return result;
    }
    private static double signedArea(float[] ring) {
        double sum = 0;
        for (int i = 0; i < ring.length; i += 2) {
            int next = (i + 2) % ring.length;
            sum += ring[i] * (double) ring[next + 1] - ring[next] * (double) ring[i + 1];
        }
        return sum / 2;
    }
    private static double area(Area shape) {
        double sum = 0;
        for (float[] ring : rings(shape)) sum += signedArea(ring);
        return Math.abs(sum);
    }

    boolean solidAt(float x, float y, float z) {
        if (y >= 0) return true;
        for (Building building : buildings)
            if (-y >= building.bottom && -y <= building.height && building.footprint.contains(x, z)) return true;
        return false;
    }

    void drawBuildings(PApplet app) {
        if (mesh == null) mesh = createMesh(app);
        app.shape(mesh);
    }
    private PShape createMesh(PApplet app) {
        PShape group = app.createShape(PApplet.GROUP);
        for (Building building : buildings) {
            PShape walls = app.createShape(); walls.beginShape(PApplet.QUADS);
            walls.noStroke(); walls.fill(235, 238, 241);
            for (float[] ring : building.rings) for (int i = 0; i < ring.length; i += 2) {
                int next = (i + 2) % ring.length;
                float x = ring[i], z = ring[i + 1], xx = ring[next], zz = ring[next + 1];
                float distance = (float) Math.hypot(xx - x, zz - z);
                if (distance < 0.0001f) continue;
                walls.normal((zz - z) / distance, 0, (x - xx) / distance);
                walls.vertex(x, -building.bottom, z); walls.vertex(xx, -building.bottom, zz);
                walls.vertex(xx, -building.height, zz); walls.vertex(x, -building.height, z);
            }
            walls.endShape(); group.addChild(walls);
            for (float[] outer : building.rings) {
                if (signedArea(outer) >= 0) continue;
                PShape roof = app.createShape(); roof.beginShape(); roof.noStroke(); roof.fill(250, 251, 252);
                roof.normal(0, -1, 0);
                for (int i = 0; i < outer.length; i += 2) roof.vertex(outer[i], -building.height, outer[i + 1]);
                Path2D.Float outerPath = path(outer);
                for (float[] hole : building.rings) if (signedArea(hole) > 0 && outerPath.contains(hole[0], hole[1])) {
                    roof.beginContour();
                    for (int i = 0; i < hole.length; i += 2) roof.vertex(hole[i], -building.height, hole[i + 1]);
                    roof.endContour();
                }
                roof.endShape(PApplet.CLOSE); group.addChild(roof);
            }
        }
        return group;
    }
    void drawRoads(PApplet app, boolean heatmap) {
        app.pushStyle(); app.noLights(); app.noFill();
        for (Road road : roads) {
            // Only draw cached street centreline segments inside the scene.
            app.stroke(heatmap ? app.color(245, 245, 245, 100) : app.color(100, 108, 115));
            app.strokeWeight(heatmap ? 1 : 3);
            for (int i = 2; i < road.points.length; i += 2) {
                float x = road.points[i - 2], z = road.points[i - 1], xx = road.points[i], zz = road.points[i + 1];
                if (Math.max(Math.abs(x), Math.abs(z)) <= SPAN / 2 && Math.max(Math.abs(xx), Math.abs(zz)) <= SPAN / 2)
                    app.line(x, -0.25f, z, xx, -0.25f, zz);
            }
        }
        app.popStyle();
    }
}
