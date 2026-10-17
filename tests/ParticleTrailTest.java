import processing.core.PApplet;
import processing.core.PVector;
import java.util.Arrays;

public class ParticleTrailTest {
    public static void main(String[] args) {
        map world = new map(new PApplet());
        for (float[] column : world.terrain) Arrays.fill(column, 0);
        stableFluids solver = new stableFluids(16, 16, 16, 40, -320, -500, -320,
            new boolean[18 * 18 * 18], 0, 0);
        setVelocity(solver, 40, -5, 20);
        world.windSnapshot = solver.latest;
        windVisualization view = new windVisualization(world);
        windVisualization.Trail trail = view.trails[0];
        trail.count = 0; trail.length = trail.clock = trail.stalledSeconds = 0;
        trail.position.set(0, -150, 0);
        trail.append((float) Math.sqrt(2025));
        assert !view.visible(trail) : "New short fragments must be hidden";
        for (int i = 0; i < 180; i++) view.update(1f / 60);
        assert Math.abs(trail.position.x - 120) < 0.01;
        assert Math.abs(trail.position.y + 165) < 0.01;
        assert Math.abs(trail.position.z - 60) < 0.01 : "Heads must follow all three simulated components";
        assert view.visible(trail) : "A sufficiently long moving trail must be visible";
        for (int i = 0; i < 180; i++) view.update(1f / 60);
        assert Math.abs(trail.position.x - 240) < 0.02;
        assert trail.x[trail.point(0)] > 30 : "The tail must expire; the inlet must not stay anchored";
        assert Math.abs(trail.speed[trail.head] - 45) < 0.001 : "Colour must use actual sampled speed";

        trail.position.set(319.99f, -150, 0);
        view.update(1f / 60);
        assert trail.count <= 1 && !view.visible(trail) : "Domain exit must clear old geometry without bridging to respawn";
        setVelocity(solver, 0, 0, 0);
        world.windSnapshot = solver.latest;
        for (int i = 0; i < 30; i++) view.update(1f / 60);
        for (windVisualization.Trail particle : view.trails)
            assert !view.visible(particle) : "Stopped particles must not leave stationary hairs";
        // Real station winds can be much slower than the old prescribed 12 m/s.
        map city = new map(new PApplet(), CityScene.load());
        city.observedWindSpeed = 3.6f;
        stableFluids slow = new stableFluids(16, 16, 16, 20, -160, -320, -160,
            new boolean[18*18*18],0,0);
        setVelocity(slow,3.6f,0,0); city.windSnapshot = slow.latest;
        windVisualization cityView = new windVisualization(city);
        windVisualization.Trail cityTrail = cityView.trails[0];
        cityTrail.count=0;cityTrail.length=cityTrail.clock=cityTrail.stalledSeconds=0;
        cityTrail.position.set(0,-150,0);cityTrail.append(3.6f);
        for(int i=0;i<1200;i++) cityView.update(1f/60);
        assert Math.abs(cityTrail.position.x-72)<0.02 : "Actual slow winds must not be sped up for display";
        assert cityView.visible(cityTrail) : "Observed station speeds need a sufficiently long visible history";
        assert cityTrail.x[cityTrail.point(0)] > 1 : "City tails must expire too";
        System.out.println("Particle trail tests passed (3D advection, moving tail, short-fragment filtering, boundary recycling, speed sampling).");
    }

    private static void setVelocity(stableFluids solver, float x, float y, float z) {
        Arrays.fill(solver.velocity[0], x);
        Arrays.fill(solver.velocity[1], y);
        Arrays.fill(solver.velocity[2], z);
        solver.publish();
    }
}
