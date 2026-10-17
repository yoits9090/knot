import java.util.Random;
import processing.core.PApplet;
import processing.core.PVector;

// Draw the same uniform XZ wind used by map's trees and leaves.
// This visual layer does not change or claim to solve the fluid dynamics.
public class windVisualization {
    private final map world;
    private final PVector[] tracers = new PVector[140];
    private final float minX, minZ, spanX, spanZ;

    windVisualization(map world) {
        this.world = world;
        minX = -world.w / 2f;
        minZ = -world.h / 2f;
        spanX = (world.cols - 1) * world.scale;
        spanZ = (world.rows - 1) * world.scale;
        Random random = new Random(42);
        for (int i = 0; i < tracers.length; i++) {
            tracers[i] = new PVector(minX + random.nextFloat() * spanX,
                -world.terrainHeight / 2 - 35 - random.nextFloat() * 100,
                minZ + random.nextFloat() * spanZ);
        }
    }

    void update(float seconds) {
        // Relative model strength has no physical speed unit. Scale transport
        // for legibility, rather than displaying an invented metres/second value.
        float travel = world.globalWindStrength * 360 * seconds;
        float dx = PApplet.cos(world.globalWindAngle) * travel;
        float dz = PApplet.sin(world.globalWindAngle) * travel;
        for (PVector tracer : tracers) {
            tracer.x = minX + wrap(tracer.x + dx - minX, spanX);
            tracer.z = minZ + wrap(tracer.z + dz - minZ, spanZ);
        }
    }

    private static float wrap(float value, float span) {
        return (value % span + span) % span;
    }

    void draw(PApplet app) {
        app.pushStyle();
        app.noLights();
        float dx = PApplet.cos(world.globalWindAngle);
        float dz = PApplet.sin(world.globalWindAngle);
        float length = 30 + world.globalWindStrength * 80;
        app.strokeWeight(1.5f);
        app.stroke(100, 225, 255, 175);
        for (PVector tracer : tracers) {
            // Trim streaks at the map boundary instead of drawing across wraps.
            float tailX = PApplet.constrain(tracer.x - dx * length, minX, minX + spanX);
            float tailZ = PApplet.constrain(tracer.z - dz * length, minZ, minZ + spanZ);
            app.line(tailX, tracer.y, tailZ, tracer.x, tracer.y, tracer.z);
        }

        // Ground-level arrows make the direction visible from any camera angle.
        app.stroke(80, 220, 255, 220);
        app.strokeWeight(2);
        for (float x = minX + 110; x < minX + spanX - 80; x += 220) {
            for (float z = minZ + 110; z < minZ + spanZ - 80; z += 220) {
                float y = world.groundHeightAt(x, z) - 55;
                float tipX = x + dx * length / 2, tipZ = z + dz * length / 2;
                app.line(x - dx * length / 2, y, z - dz * length / 2, tipX, y, tipZ);
                app.line(tipX, y, tipZ, tipX - dx * 14 - dz * 7, y, tipZ - dz * 14 + dx * 7);
                app.line(tipX, y, tipZ, tipX - dx * 14 + dz * 7, y, tipZ - dz * 14 - dx * 7);
            }
        }
        app.popStyle();
    }
}
