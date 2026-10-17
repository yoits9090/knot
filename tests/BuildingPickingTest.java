import processing.core.*;
import processing.event.MouseEvent;
import java.awt.geom.Area;
import java.util.*;

public class BuildingPickingTest {
    public static void main(String[] args) throws Exception {
        TorontoAtlas atlas=TorontoAtlas.load();
        TorontoAtlas.Hit roof=null,wall=null;
        float roofX=0,roofZ=0;
        for(TorontoAtlas.Volume v:atlas.query(-1500,1500,2500)) {
            if(v.height<80)continue;
            float x=(v.x0+v.x1)/2,z=(v.z0+v.z1)/2;
            if(!v.footprint(0,0).contains(x,z)||!v.footprint(0,0).contains(x+1,z))continue;
            TorontoAtlas.Hit hit=atlas.raycast(new PVector(x,-800,z),new PVector(0,1,0));
            if(hit==null||hit.building==null||hit.normal.y!= -1)continue;
            if(atlas.segmentBlocked(hit.endpoint(),hit.endpoint(),TorontoRoute.CLEARANCE))continue;
            roof=hit;roofX=x;roofZ=z;break;
        }
        assert roof!=null;
        float expectedHeight=0;
        for(TorontoAtlas.Volume v:atlas.query(roofX-.01f,roofZ-.01f,.02f))
            if(v.footprint(0,0).contains(roofX,roofZ))expectedHeight=Math.max(expectedHeight,v.height);
        assert Math.abs(roof.point.y+expectedHeight)<.001 : "Nearest roof must occlude the ground and lower buildings";
        assert Math.abs(roof.endpoint().y-roof.point.y+3)<.001;
        assert Math.abs(roof.point.x-roofX)<.001&&Math.abs(roof.point.z-roofZ)<.001;
        assert atlas.raycast(new PVector(atlas.minX-100,-100,atlas.minZ-100),new PVector(0,1,0))==null;
        assert atlas.raycast(new PVector(roofX,-800,roofZ),new PVector(0,-1,0))==null;

        outer: for(TorontoAtlas.Volume v:atlas.query(-1500,1500,2500)) {
            if(v.height<80)continue;
            Area area=v.footprint(0,0);
            for(float[] ring:v.rings)for(int i=0;i<ring.length;i+=2) {
                int j=(i+2)%ring.length;float dx=ring[j]-ring[i],dz=ring[j+1]-ring[i+1];
                float length=(float)Math.hypot(dx,dz);if(length<15)continue;
                float x=(ring[i]+ring[j])/2,z=(ring[i+1]+ring[j+1])/2;
                PVector normal=new PVector(dz/length,0,-dx/length);
                if(area.contains(x+normal.x*.1f,z+normal.z*.1f))normal.mult(-1);
                PVector origin=new PVector(x+normal.x*3,-v.height*.6f,z+normal.z*3);
                if(atlas.segmentBlocked(origin,origin,1))continue;
                TorontoAtlas.Hit hit=atlas.raycast(origin,PVector.mult(normal,-1));
                if(hit!=null&&hit.normal.y==0&&hit.distance<3.1f){wall=hit;break outer;}
            }
        }
        assert wall!=null : "A horizontal ray must hit a wall even when it never hits the ground plane";
        assert wall.point.y<0&&wall.normal.mag()>0.99;
        assert !atlas.segmentBlocked(wall.endpoint(),wall.endpoint(),1);
        // The wall must also win when the same angled ray eventually reaches the ground.
        PVector angledOrigin=PVector.add(wall.point,PVector.mult(wall.normal,3));angledOrigin.y-=1;
        TorontoAtlas.Hit angled=atlas.raycast(angledOrigin,PVector.sub(wall.point,angledOrigin));
        assert angled!=null&&angled.building!=null&&angled.normal.y==0;
        assert PVector.dist(angled.point,wall.point)<.01;

        int holes=0;
        for(TorontoAtlas.Volume v:atlas.buildings) {
            if(v.rings.length<2)continue;
            Area area=v.footprint(0,0);
            for(float[] ring:v.rings) {
                float x=0,z=0;for(int i=0;i<ring.length;i+=2){x+=ring[i];z+=ring[i+1];}
                x/=ring.length/2;z/=ring.length/2;
                if(x<=v.x0||x>=v.x1||z<=v.z0||z>=v.z1||area.contains(x,z))continue;
                TorontoAtlas.Hit hit=atlas.raycast(new PVector(x,-800,z),new PVector(0,1,0));
                assert hit==null||hit.building!=v : "Courtyard holes must not become solid roofs";
                holes++;break;
            }
            if(holes==5)break;
        }
        assert holes>0;
        src sketch=new src();
        try {
            sketch.atlas=atlas;sketch.width=800;sketch.height=600;sketch.showWind=false;
            sketch.world=new map(new PApplet(),CityScene.window(atlas,roofX,roofZ,640));
            clickVertical(sketch,roofX,roofZ);
            assert sketch.start!=null&&sketch.pickedPoint!=null;
            assert sketch.pathStatus.contains("roof") : sketch.pathStatus;
            assert Math.abs(sketch.pickedPoint.y+expectedHeight+3)<.001 : "Do not replace a roof with a 50m intersection";
            assert Math.abs(sketch.pickedPoint.x-roofX)<.001;
            // An exact same-cell surface goal remains a separate endpoint.
            clickVertical(sketch,roofX+1,roofZ);
            assert sketch.goal!=null&&sketch.search!=null : sketch.pathStatus;
            for(int i=0;i<100&&!sketch.search.done;i++)sketch.search.step();
            assert sketch.search.done&&!sketch.search.path.isEmpty();
            assert PVector.dist(sketch.cityRoute.point(sketch.start),sketch.cityRoute.point(sketch.goal))>.9;
            for(int i=1;i<sketch.search.path.size();i++)
                assert !atlas.segmentBlocked(sketch.cityRoute.point(sketch.search.path.get(i-1)),sketch.cityRoute.point(sketch.search.path.get(i)),1);
        } finally {
            if(sketch.world!=null)sketch.world.close();
            sketch.pathWorker.shutdownNow();sketch.mapWorker.shutdownNow();sketch.weatherWorker.shutdownNow();
        }
        System.out.printf("Building picking passed: nearest roof %.1fm, horizontal/angled wall, %d courtyard holes, miss/upward rays, asynchronous screen clicks and exact same-cell endpoints.%n",expectedHeight,holes);
    }
    static void clickVertical(src sketch,float x,float z)throws Exception {
        sketch.inverseViewProjection=new PMatrix3D(1,0,0,x, 0,0,400,-300, 0,1,0,z, 0,0,0,1);
        sketch.mousePressed(new MouseEvent(null,0,MouseEvent.PRESS,0,400,300,PApplet.LEFT,1));
        assert sketch.pendingCityPick!=null;
        sketch.pendingCityPick.get(3,java.util.concurrent.TimeUnit.SECONDS);
        sketch.finishCityPick();
    }
}
