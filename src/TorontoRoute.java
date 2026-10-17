import java.util.*;
import processing.core.PVector;

/** Sparse citywide 3D route grid, with real street-intersection endpoints. */
final class TorontoRoute {
    static final float CELL=10;
    final TorontoAtlas atlas;
    final TorontoAtlas.Intersection from,to;
    final float fromAltitude,toAltitude;
    final int[] start,goal;
    final SearchPlayback search;
    final PVector startPoint,goalPoint;
    static final float CLEARANCE=1;
    TorontoRoute(TorontoAtlas atlas,TorontoAtlas.Intersection from,TorontoAtlas.Intersection to,float a,float b){
        this(atlas,from,to,new PVector(from.x,-a,from.z),new PVector(to.x,-b,to.z));
    }
    TorontoRoute(TorontoAtlas atlas,PVector from,PVector to,String fromLabel,String toLabel){
        this(atlas,new TorontoAtlas.Intersection(-1,from.x,from.z,fromLabel),new TorontoAtlas.Intersection(-2,to.x,to.z,toLabel),from,to);
    }
    private TorontoRoute(TorontoAtlas atlas,TorontoAtlas.Intersection from,TorontoAtlas.Intersection to,PVector a,PVector b){
        this.atlas=atlas;this.from=from;this.to=to;startPoint=a.copy();goalPoint=b.copy();
        start=cell(a.x,-a.y,a.z);goal=cell(b.x,-b.y,b.z);
        // Distinct nearby surface points can share a cell. Give the exact goal
        // its own adjacent graph node so its connector is checked and rendered.
        if(Arrays.equals(start,goal)&&PVector.dist(a,b)>.001f)goal[1]=start[1]<64?start[1]+1:start[1]-1;
        fromAltitude=-a.y;toAltitude=-b.y;
        search=new SearchPlayback((int)Math.ceil((atlas.maxX-atlas.minX)/CELL),65,(int)Math.ceil((atlas.maxZ-atlas.minZ)/CELL),
            new SearchPlayback.Blocker() {
                public boolean blocked(int x,int y,int z) {
                    PVector p=point(new int[]{x,y,z});
                    return atlas.segmentBlocked(p,p,CLEARANCE);
                }
                public boolean edgeBlocked(int x,int y,int z,int xx,int yy,int zz) {
                    return atlas.segmentBlocked(point(new int[]{x,y,z}),point(new int[]{xx,yy,zz}),CLEARANCE);
                }
            },start,goal);
    }
    int[] cell(float x,float altitude,float z){return new int[]{(int)((x-atlas.minX)/CELL),Math.max(0,Math.round(altitude/CELL)-1),(int)((z-atlas.minZ)/CELL)};}
    PVector point(int[] n){
        float x=atlas.minX+(n[0]+.5f)*CELL,z=atlas.minZ+(n[2]+.5f)*CELL;
        if(Arrays.equals(n,start))return startPoint.copy();
        if(Arrays.equals(n,goal))return goalPoint.copy();
        return new PVector(x,-(n[1]+1)*CELL,z);
    }
    static TorontoRoute random(TorontoAtlas atlas,Random r){
        for(int attempt=0;attempt<3000;attempt++){
            TorontoAtlas.Intersection a=atlas.intersections.get(r.nextInt(atlas.intersections.size()));
            // Uniform citywide origin; choose a different mapped junction within a readable route length.
            List<TorontoAtlas.Intersection> nearby=new ArrayList<>();
            for(TorontoAtlas.Intersection b:atlas.intersections){double d=Math.hypot(a.x-b.x,a.z-b.z);if(d>=500&&d<=1800)nearby.add(b);}
            if(nearby.isEmpty())continue;
            TorontoAtlas.Intersection b=nearby.get(r.nextInt(nearby.size()));
            TorontoRoute route=new TorontoRoute(atlas,a,b,30+r.nextInt(8)*20,30+r.nextInt(8)*20);
            if(!route.search.done)return route;
        }throw new IllegalStateException("No free random intersection pair");
    }
}
