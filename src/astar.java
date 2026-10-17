/*
 author: abas
 date: 10/17/2026
 desc: Six-direction A* over a 3D grid of cubic cells.
 */
import java.util.*;

public class astar {
    private static final int[][] DIRECTIONS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
        {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static class Entry {
        final int id, g, f;
        Entry(int id, int g, int h) {
            this.id = id;
            this.g = g;
            this.f = g + h;
        }
    }

    public static List<int[]> findPath(boolean[][][] blocked,
            int sx, int sy, int sz, int gx, int gy, int gz) {
        int width = blocked.length;
        if (width == 0 || blocked[0].length == 0 || blocked[0][0].length == 0)
            return Collections.emptyList();
        int height = blocked[0].length, depth = blocked[0][0].length;
        if (!inside(sx, sy, sz, width, height, depth)
                || !inside(gx, gy, gz, width, height, depth)
                || blocked[sx][sy][sz] || blocked[gx][gy][gz])
            return Collections.emptyList();

        int count = width * height * depth;
        int[] best = new int[count], parent = new int[count];
        Arrays.fill(best, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);
        int start = (sx * height + sy) * depth + sz;
        int goal = (gx * height + gy) * depth + gz;
        PriorityQueue<Entry> open = new PriorityQueue<>(
            Comparator.comparingInt((Entry e) -> e.f)
                .thenComparingInt(e -> -e.g));
        best[start] = 0;
        open.add(new Entry(start, 0, distance(sx, sy, sz, gx, gy, gz)));

        while (!open.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
            Entry current = open.poll();
            if (current.g != best[current.id]) continue;
            if (current.id == goal) {
                List<int[]> path = new ArrayList<>();
                for (int id = goal; id != -1; id = parent[id])
                    path.add(new int[]{id / (height * depth), (id / depth) % height, id % depth});
                Collections.reverse(path);
                return path;
            }
            int x = current.id / (height * depth);
            int y = (current.id / depth) % height;
            int z = current.id % depth;
            for (int[] d : DIRECTIONS) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (!inside(nx, ny, nz, width, height, depth) || blocked[nx][ny][nz]) continue;
                int id = (nx * height + ny) * depth + nz;
                int next = current.g + 1;
                if (next >= best[id]) continue;
                best[id] = next;
                parent[id] = current.id;
                open.add(new Entry(id, next, distance(nx, ny, nz, gx, gy, gz)));
            }
        }
        return Collections.emptyList();
    }

    private static int distance(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(gx - x) + Math.abs(gy - y) + Math.abs(gz - z);
    }

    private static boolean inside(int x, int y, int z, int w, int h, int d) {
        return x >= 0 && x < w && y >= 0 && y < h && z >= 0 && z < d;
    }
}
