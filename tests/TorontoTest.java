import java.util.*;
import processing.core.*;

public class TorontoTest {
    public static void main(String[] args) throws Exception {
        long t=System.nanoTime();TorontoAtlas atlas=TorontoAtlas.load();
        assert atlas.buildings.size()==428184;
        assert atlas.intersections.size()>18000;
        assert atlas.minZ < -20000 && atlas.maxZ > 9000 && atlas.maxX-atlas.minX > 44000;
        assert atlas.intersections.stream().anyMatch(i->i.name.contains("Steeles"));
        assert atlas.intersections.stream().anyMatch(i->i.name.contains("Queens Quay"));
        assert atlas.intersections.stream().anyMatch(i->i.name.contains("Bloor")&&i.name.contains("Yonge"));
        System.out.printf("Atlas: %,d buildings / %,d streets / %,d intersections, loaded %.0f ms%n",atlas.buildings.size(),atlas.roads.size(),atlas.intersections.size(),(System.nanoTime()-t)/1e6);
        Random rng=new Random(28);
        int maxExpansions=0;
        for(int n=0;n<12;n++){
            TorontoRoute route=TorontoRoute.random(atlas,rng);
            assert route.from.id!=route.to.id;
            assert route.fromAltitude>=30&&route.toAltitude<=170;
            route.search.update(.049f);assert route.search.expanded.isEmpty();
            route.search.update(.002f);assert route.search.expanded.size()==1;
            for(int i=0;i<150000&&!route.search.done;i++)route.search.step();
            assert route.search.done&&!route.search.path.isEmpty():"Random air route must connect: "+route.search.error;
            for(int[] cell:route.search.path){PVector p=route.point(cell);assert !atlas.blocked(p.x,-p.y,p.z,0.01f);}
            maxExpansions=Math.max(maxExpansions,route.search.expanded.size());
        }
        // Regression: the old 20 m full-cell solids rejected downtown street endpoints.
        int[] downtownIds={13468146,13467722,13466960,13467239};
        java.util.Map<Integer,TorontoAtlas.Intersection> downtown=new java.util.HashMap<>();
        for(TorontoAtlas.Intersection junction:atlas.intersections)
            for(int id:downtownIds)if(junction.id==id)downtown.put(id,junction);
        for(int n=0;n<downtownIds.length-1;n++) {
            TorontoRoute route=new TorontoRoute(atlas,downtown.get(downtownIds[n]),downtown.get(downtownIds[n+1]),50,70);
            assert !route.search.done : "Downtown street endpoints must be usable: "+route.search.error;
            for(int k=0;k<30000&&!route.search.done;k++)route.search.step();
            assert route.search.done&&!route.search.path.isEmpty() : "Downtown route must connect";
            for(int k=1;k<route.search.path.size();k++)
                assert !atlas.segmentBlocked(route.point(route.search.path.get(k-1)),route.point(route.search.path.get(k)),TorontoRoute.CLEARANCE)
                    : "Every segment, including exact intersection connectors, must clear buildings";
            System.out.println("Downtown: "+route.from.name+" -> "+route.to.name+" | "+route.search.expanded.size()+" expansions");
        }
        src sketch=new src();
        try {
            sketch.atlas=atlas;
            sketch.selectCityIntersection(downtown.get(13468146));
            assert sketch.start!=null : "Downtown start must not silently disappear";
            sketch.selectCityIntersection(downtown.get(13467722));
            assert sketch.search!=null&&!sketch.search.done : sketch.pathStatus;
            // Genuine blocked locations must report failure instead of claiming search started.
            TorontoAtlas.Volume building=atlas.buildings.stream().filter(v->v.height>100&&v.footprint(0,0).contains((v.x0+v.x1)/2,(v.z0+v.z1)/2)).findFirst().orElseThrow();
            sketch.goal=null;sketch.pickedIntersection=null;
            sketch.selectCityIntersection(new TorontoAtlas.Intersection(-1,(building.x0+building.x1)/2,(building.z0+building.z1)/2,"inside a tower"));
            assert sketch.start==null&&sketch.pathStatus.contains("blocked");
        } finally {sketch.mapWorker.shutdownNow();sketch.pathWorker.shutdownNow();sketch.weatherWorker.shutdownNow();}
        // Check true incremental A* against the original optimal 3D algorithm.
        boolean[][][] mask=new boolean[6][4][6];for(int z=0;z<6;z++)mask[3][2][z]=true;
        SearchPlayback search=new SearchPlayback(6,4,6,(x,y,z)->mask[x][y][z],new int[]{0,2,2},new int[]{5,2,2});
        while(!search.done)search.step();assert search.path.size()==astar.findPath(mask,0,2,2,5,2,2).size();
        assert !search.rejected.isEmpty();
        CameraRig camera=new CameraRig();camera.held.add((int)'W');float oldZ=camera.z;camera.update(.1f);assert camera.z<oldZ;
        camera.held.clear();camera.held.add(PApplet.RIGHT);float oldX=camera.x,oldYaw=camera.yaw;camera.update(.1f);assert camera.x==oldX&&camera.yaw>oldYaw;
        camera.held.clear();camera.held.add((int)'E');float oldY=camera.y;camera.update(.1f);assert camera.y<oldY;
        camera.route(100,200,800,900);assert camera.bird&&camera.x==450&&camera.z==550;
        WeatherData weather=new WeatherData();assert weather.history.size()>2000;
        WeatherData.Observation north=new WeatherData.Observation("2026-10-17T00:00:00Z",10,0),east=new WeatherData.Observation("2026-10-17T00:00:00Z",10,90);
        assert north.south==10&&Math.abs(north.east)<.0001;
        assert east.east==-10&&Math.abs(east.south)<.0001;
        assert weather.backtest.getInt("test_pairs")==588;
        assert Math.abs(weather.backtest.getJSONObject("metrics").getJSONObject("persistence").getDouble("speed_mae_ms")-1.0926870748)<1e-6;
        CityScene local=CityScene.window(atlas,0,0,640);assert local.buildings.size()>100;
        map world=new map(new PApplet(),local);world.observedWindSpeed=weather.current.speed;world.globalWindAngle=weather.current.angle();world.startWind();
        try{
            assert Math.abs(world.wind.solver.inletX-weather.current.east)<1e-5;
            assert Math.abs(world.wind.solver.inletZ-weather.current.south)<1e-5;
            assert world.wind.failure==null;
        }finally{world.close();}
        System.out.println("Toronto tests passed: spatial coverage, random intersection routes/altitudes, solid collisions, visible A* timing/optimality, camera movement, actual weather forcing. Max route expansions: "+maxExpansions);
    }
}
