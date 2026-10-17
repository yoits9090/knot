import processing.core.PVector;

// Static collision volume and picking for the exact terrain mesh drawn by src.
public class navigation {
    final map world;
    final float cell, minX, minY, minZ;
    final boolean[][][] blocked;

    navigation(map world) {
        this.world = world;
        cell = world.scale;
        minX = -world.w / 2f;
        minZ = -world.h / 2f;
        // Enough headroom to travel above the tallest tree, with room below ground.
        minY = world.city == null ? -world.terrainHeight / 2f - 240 : world.airTop();
        int layers = (int) Math.ceil((world.terrainHeight / 2f + cell - minY) / cell);
        blocked = new boolean[world.cols - 1][layers][world.rows - 1];
        buildObstacles();
        addCityObstacles();
    }

    private void buildObstacles() {
        float half = cell / 2;
        for (int x = 0; x < blocked.length; x++) {
            for (int z = 0; z < blocked[0][0].length; z++) {
                // Each column spans one rendered terrain quad. Its highest corner
                // bounds both triangles because their heights interpolate linearly.
                float highest = Math.min(Math.min(world.terrain[x][z], world.terrain[x + 1][z]),
                    Math.min(world.terrain[x][z + 1], world.terrain[x + 1][z + 1]));
                for (int y = 0; y < blocked[0].length; y++)
                    blocked[x][y][z] = minY + (y + 1) * cell >= highest;
            }
        }
        // Reserve each tree's complete sway envelope so ordinary wind cannot
        // move its trunk or canopy into the static route.
        for (map.Tree tree : world.trees) {
            float ground = world.groundHeightAt(tree.x, tree.z);
            float top = ground - tree.baseHeight;
            float sway = tree.swayAmp * 0.6f * 0.5f * 80;
            float trunkRadius = 3 + sway;
            float canopyRadius = 18 + sway;
            int lowX = Math.max(0, (int) Math.floor((tree.x - canopyRadius - minX) / cell));
            int highX = Math.min(blocked.length - 1, (int) Math.floor((tree.x + canopyRadius - minX) / cell));
            int lowZ = Math.max(0, (int) Math.floor((tree.z - canopyRadius - minZ) / cell));
            int highZ = Math.min(blocked[0][0].length - 1, (int) Math.floor((tree.z + canopyRadius - minZ) / cell));
            for (int x = lowX; x <= highX; x++) {
                for (int z = lowZ; z <= highZ; z++) {
                    // Distance from tree axis to the nearest point of the cube.
                    float dx = Math.max(0, Math.abs(minX + (x + 0.5f) * cell - tree.x) - half);
                    float dz = Math.max(0, Math.abs(minZ + (z + 0.5f) * cell - tree.z) - half);
                    for (int y = 0; y < blocked[0].length; y++) {
                        float centerY = minY + (y + 0.5f) * cell;
                        float dy = Math.max(0, Math.abs(centerY - top) - half);
                        boolean canopy = dx * dx + dy * dy + dz * dz <= canopyRadius * canopyRadius;
                        boolean trunk = dx * dx + dz * dz <= trunkRadius * trunkRadius
                            && centerY + half >= top && centerY - half <= ground;
                        if (canopy || trunk) blocked[x][y][z] = true;
                    }
                }
            }
        }
    }
    private void addCityObstacles() {
        if (world.city == null) return;
        for (CityScene.Building building : world.city.buildings) {
            int x0 = Math.max(0, (int) Math.floor((building.bounds.getMinX() - minX) / cell));
            int x1 = Math.min(blocked.length - 1, (int) Math.floor((building.bounds.getMaxX() - minX) / cell));
            int z0 = Math.max(0, (int) Math.floor((building.bounds.getMinY() - minZ) / cell));
            int z1 = Math.min(blocked[0][0].length - 1, (int) Math.floor((building.bounds.getMaxY() - minZ) / cell));
            int y0 = Math.max(0, (int) Math.floor((-building.height - minY) / cell));
            int y1 = Math.min(blocked[0].length - 1, (int) Math.floor((-building.bottom - minY) / cell));
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                if (!building.overlaps(minX + x * cell, minZ + z * cell, cell)) continue;
                for (int y = y0; y <= y1; y++) blocked[x][y][z] = true;
            }
        }
    }

    PVector position(int[] node) {
        return new PVector(minX + (node[0] + 0.5f) * cell,
            minY + (node[1] + 0.5f) * cell, minZ + (node[2] + 0.5f) * cell);
    }

    // Pick the lowest available voxel above the clicked surface, in its column.
    int[] endpoint(PVector hit) {
        int x = (int) Math.floor((hit.x - minX) / cell);
        int z = (int) Math.floor((hit.z - minZ) / cell);
        x = Math.max(0, Math.min(blocked.length - 1, x));
        z = Math.max(0, Math.min(blocked[0][0].length - 1, z));
        for (int y = blocked[0].length - 1; y >= 0; y--) {
            if (!blocked[x][y][z] && position(new int[]{x, y, z}).y < hit.y)
                return new int[]{x, y, z};
        }
        return null;
    }

    // Ray/triangle intersection on the same diagonal as the TRIANGLE_STRIP.
    // Trees are collision obstacles; clicks intentionally select the ground.
    PVector raycast(PVector origin, PVector direction) {
        float nearest = Float.POSITIVE_INFINITY;
        for (int x = 0; x < world.cols - 1; x++) {
            if (Thread.currentThread().isInterrupted()) return null;
            for (int z = 0; z < world.rows - 1; z++) {
                PVector a = vertex(x, z), b = vertex(x, z + 1);
                PVector c = vertex(x + 1, z), d = vertex(x + 1, z + 1);
                nearest = Math.min(nearest, triangleDistance(origin, direction, a, b, c));
                nearest = Math.min(nearest, triangleDistance(origin, direction, c, b, d));
            }
        }
        return Float.isFinite(nearest) ? PVector.add(origin, PVector.mult(direction, nearest)) : null;
    }

    private PVector vertex(int x, int z) {
        return new PVector(minX + x * cell, world.terrain[x][z], minZ + z * cell);
    }

    private static float triangleDistance(PVector origin, PVector direction,
            PVector a, PVector b, PVector c) {
        PVector edge1 = PVector.sub(b, a), edge2 = PVector.sub(c, a);
        PVector p = direction.cross(edge2);
        float determinant = edge1.dot(p);
        if (Math.abs(determinant) < 0.000001f) return Float.POSITIVE_INFINITY;
        float inverse = 1 / determinant;
        PVector offset = PVector.sub(origin, a);
        float u = offset.dot(p) * inverse;
        if (u < 0 || u > 1) return Float.POSITIVE_INFINITY;
        PVector q = offset.cross(edge1);
        float v = direction.dot(q) * inverse;
        if (v < 0 || u + v > 1) return Float.POSITIVE_INFINITY;
        float distance = edge2.dot(q) * inverse;
        return distance >= 0 ? distance : Float.POSITIVE_INFINITY;
    }
}
