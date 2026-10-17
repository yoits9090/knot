import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.awt.geom.*;
import processing.core.*;

/** Citywide compact geometry; spatial buckets support local CFD and sparse routing. */
final class TorontoAtlas {
    static final float TILE = 500;
    final float minX,minZ,maxX,maxZ;
    final List<Volume> buildings = new ArrayList<>();
    final List<CityScene.Road> roads = new ArrayList<>();
    final List<Intersection> intersections = new ArrayList<>();
    final Map<Long,List<Volume>> buckets = new HashMap<>();
    final LinkedHashMap<Long,PShape> details = new LinkedHashMap<>(32,.75f,true);
    private PImage overview;
    private PShape skyline;
    int renderedDetailed;
    static final class Volume {
        final float height,x0,z0,x1,z1;
        final float[][] rings;
        Volume(float height,float[][] rings) {
            this.height=height;this.rings=rings;
            float a=Float.POSITIVE_INFINITY,b=a,c=-a,d=-a;
            for(float[] ring:rings)for(int i=0;i<ring.length;i+=2){a=Math.min(a,ring[i]);b=Math.min(b,ring[i+1]);c=Math.max(c,ring[i]);d=Math.max(d,ring[i+1]);}
            x0=a;z0=b;x1=c;z1=d;
        }
        Area footprint(float offsetX,float offsetZ) {
            Path2D.Float p=new Path2D.Float(Path2D.WIND_EVEN_ODD);
            for(float[] ring:rings){p.moveTo(ring[0]-offsetX,ring[1]-offsetZ);for(int i=2;i<ring.length;i+=2)p.lineTo(ring[i]-offsetX,ring[i+1]-offsetZ);p.closePath();}
            return new Area(p);
        }
        boolean overlaps(float x,float z,float size) {
            return x1>=x&&x0<=x+size&&z1>=z&&z0<=z+size&&footprint(0,0).intersects(x,z,size,size);
        }
    }
    static final class Intersection {
        final int id; final float x,z; final String name;
        Intersection(int id,float x,float z,String name){this.id=id;this.x=x;this.z=z;this.name=name;}
    }
    private TorontoAtlas(DataInputStream in) throws IOException {
        if(in.readInt()!=0x544f5231)throw new IOException("Unsupported Toronto atlas");
        minX=in.readFloat();minZ=in.readFloat();maxX=in.readFloat();maxZ=in.readFloat();
        int count=in.readInt();
        for(int n=0;n<count;n++){
            float h=in.readFloat();float[][] rings=new float[in.readInt()][];
            for(int r=0;r<rings.length;r++){float[] ring=new float[in.readInt()*2];for(int i=0;i<ring.length;i++)ring[i]=in.readFloat();rings[r]=ring;}
            Volume v=new Volume(h,rings);buildings.add(v);
            for(int x=tile(v.x0);x<=tile(v.x1);x++)for(int z=tile(v.z0);z<=tile(v.z1);z++)buckets.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(v);
        }
        count=in.readInt();for(int n=0;n<count;n++){
            String name=string(in);float[] points=new float[in.readInt()*2];for(int i=0;i<points.length;i++)points[i]=in.readFloat();roads.add(new CityScene.Road(name,points,8));
        }
        count=in.readInt();for(int n=0;n<count;n++)intersections.add(new Intersection(in.readInt(),in.readFloat(),in.readFloat(),string(in)));
    }
    static TorontoAtlas load(){try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(Paths.get("data/toronto/atlas.bin.gz")))))){return new TorontoAtlas(in);}catch(IOException e){throw new IllegalStateException("Toronto atlas missing or invalid. Run scripts/cache-toronto.py",e);}}
    private static String string(DataInputStream in)throws IOException {int n=in.readInt();if(n<0||n>100000)throw new IOException("Invalid string");return new String(in.readNBytes(n),java.nio.charset.StandardCharsets.UTF_8);}
    static int tile(float x){return (int)Math.floor(x/TILE);}
    static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    List<Volume> query(float x,float z,float size){Set<Volume> set=new LinkedHashSet<>();for(int i=tile(x);i<=tile(x+size);i++)for(int j=tile(z);j<=tile(z+size);j++){List<Volume> b=buckets.get(key(i,j));if(b!=null)for(Volume v:b)if(v.x1>=x&&v.x0<=x+size&&v.z1>=z&&v.z0<=z+size)set.add(v);}return new ArrayList<>(set);}
    boolean blocked(float x,float altitude,float z,float cell){
        if(x<minX||x>maxX||z<minZ||z>maxZ||altitude<cell/2||altitude>650)return true;
        // Check all buckets touched by the cell, including tile boundaries.
        for(Volume v:query(x-cell/2,z-cell/2,cell))if(v.height>=altitude-cell/2&&v.overlaps(x-cell/2,z-cell/2,cell))return true;
        return false;
    }
    boolean segmentBlocked(PVector a,PVector b,float clearance) {
        float x0=Math.min(a.x,b.x)-clearance,z0=Math.min(a.z,b.z)-clearance;
        float width=Math.abs(a.x-b.x)+2*clearance,depth=Math.abs(a.z-b.z)+2*clearance;
        float lowAltitude=Math.min(-a.y,-b.y)-clearance;
        if(x0<minX||x0+width>maxX||z0<minZ||z0+depth>maxZ||lowAltitude<0||Math.max(-a.y,-b.y)+clearance>650)return true;
        for(Volume v:query(x0,z0,Math.max(width,depth)))
            if(v.height>=lowAltitude&&v.footprint(0,0).intersects(x0,z0,width,depth))return true;
        return false;
    }
    static final class Hit {
        final PVector point,normal;
        final float distance;
        final Volume building; // null is ground
        Hit(PVector point,PVector normal,float distance,Volume building){this.point=point;this.normal=normal;this.distance=distance;this.building=building;}
        String label(){return building==null?"Ground":normal.y<-.5f?"Building roof":"Building wall";}
        PVector endpoint(){return PVector.add(point,PVector.mult(normal,3));}
    }
    Hit raycast(PVector origin,PVector ray) {
        if(ray.magSq()<1e-12f)return null;
        PVector direction=ray.copy().normalize();
        float[] range={0,120000};
        if(!clipRay(origin.x,direction.x,minX,maxX,range)||!clipRay(origin.z,direction.z,minZ,maxZ,range)
                ||!clipRay(origin.y,direction.y,-650,0,range))return null;
        Hit nearest=null;
        if(direction.y>1e-7f){float t=-origin.y/direction.y;
            if(t>=range[0]&&t<=range[1])nearest=new Hit(PVector.add(origin,PVector.mult(direction,t)),new PVector(0,-1,0),t,null);
        }
        float t=range[0];
        float px=origin.x+direction.x*(t+.001f),pz=origin.z+direction.z*(t+.001f);
        int tx=tile(px),tz=tile(pz),stepX=direction.x>0?1:-1,stepZ=direction.z>0?1:-1;
        float nextX=Math.abs(direction.x)<1e-8f?Float.POSITIVE_INFINITY:(((tx+(stepX>0?1:0))*TILE-origin.x)/direction.x);
        float nextZ=Math.abs(direction.z)<1e-8f?Float.POSITIVE_INFINITY:(((tz+(stepZ>0?1:0))*TILE-origin.z)/direction.z);
        float deltaX=Math.abs(direction.x)<1e-8f?Float.POSITIVE_INFINITY:TILE/Math.abs(direction.x);
        float deltaZ=Math.abs(direction.z)<1e-8f?Float.POSITIVE_INFINITY:TILE/Math.abs(direction.z);
        Set<Volume> seen=new HashSet<>();
        while(t<=range[1] && (nearest==null || t<=nearest.distance+.001f)) {
            if(Thread.currentThread().isInterrupted())return null;
            List<Volume> bucket=buckets.get(key(tx,tz));
            if(bucket!=null)for(Volume v:bucket)if(seen.add(v)){
                Hit hit=hitBuilding(origin,direction,v,nearest==null?range[1]:nearest.distance);
                if(hit!=null&&(nearest==null||hit.distance<nearest.distance))nearest=hit;
            }
            if(nextX==Float.POSITIVE_INFINITY&&nextZ==Float.POSITIVE_INFINITY)break;
            if(nextX<nextZ){t=nextX;nextX+=deltaX;tx+=stepX;}
            else {t=nextZ;nextZ+=deltaZ;tz+=stepZ;}
        }
        return nearest;
    }
    private static boolean clipRay(float origin,float direction,float low,float high,float[] range){
        if(Math.abs(direction)<1e-8f)return origin>=low&&origin<=high;
        float a=(low-origin)/direction,b=(high-origin)/direction;
        range[0]=Math.max(range[0],Math.min(a,b));range[1]=Math.min(range[1],Math.max(a,b));return range[0]<=range[1];
    }
    private static Hit hitBuilding(PVector origin,PVector direction,Volume v,float limit){
        float[] range={0,limit};
        if(!clipRay(origin.x,direction.x,v.x0,v.x1,range)||!clipRay(origin.z,direction.z,v.z0,v.z1,range)
                ||!clipRay(origin.y,direction.y,-v.height,0,range))return null;
        Area footprint=v.footprint(0,0);Hit best=null;
        if(Math.abs(direction.y)>1e-8f){float t=(-v.height-origin.y)/direction.y;
            float x=origin.x+direction.x*t,z=origin.z+direction.z*t;
            if(t>=0&&t<=limit&&footprint.contains(x,z))best=new Hit(new PVector(x,-v.height,z),new PVector(0,-1,0),t,v);
        }
        for(float[] ring:v.rings)for(int i=0;i<ring.length;i+=2){
            int j=(i+2)%ring.length;double ex=ring[j]-ring[i],ez=ring[j+1]-ring[i+1];
            double denominator=direction.x*ez-direction.z*ex;
            if(Math.abs(denominator)<1e-10)continue;
            double ax=ring[i]-origin.x,az=ring[i+1]-origin.z;
            double t=(ax*ez-az*ex)/denominator,u=(ax*direction.z-az*direction.x)/denominator;
            if(t<0||t>limit||best!=null&&t>=best.distance||u<0||u>1)continue;
            float y=origin.y+(float)t*direction.y;if(y< -v.height-.001f||y>0)continue;
            float length=(float)Math.hypot(ex,ez);if(length<1e-7f)continue;
            PVector normal=new PVector((float)ez/length,0,(float)-ex/length);
            if(footprint.contains((ring[i]+ring[j])/2+normal.x*.1f,(ring[i+1]+ring[j+1])/2+normal.z*.1f))normal.mult(-1);
            best=new Hit(PVector.add(origin,PVector.mult(direction,(float)t)),normal,(float)t,v);
        }
        return best;
    }
    void draw(PApplet app,float focusX,float focusZ,float altitude){
        app.pushStyle();app.noLights();app.noStroke();
        if(overview==null)overview=app.loadImage(Paths.get("data/toronto/overview.png").toAbsolutePath().toString());
        app.textureMode(PApplet.NORMAL);app.beginShape(PApplet.QUADS);app.texture(overview);
        app.vertex(minX,1,minZ,0,0);app.vertex(maxX,1,minZ,1,0);app.vertex(maxX,1,maxZ,1,1);app.vertex(minX,1,maxZ,0,1);app.endShape();
        app.noLights();app.ambientLight(65,65,75);app.directionalLight(155,155,155,.3f,1,-.4f);
        if(altitude>3500){
            if(skyline==null)skyline=skyline(app);
            app.shape(skyline);renderedDetailed=0;
        }else{
            int tx=tile(focusX),tz=tile(focusZ),made=0;renderedDetailed=0;
            // Build at most one uncached tile per frame, closest tiles first.
            List<int[]> tiles=new ArrayList<>();
            for(int x=tx-2;x<=tx+2;x++)for(int z=tz-2;z<=tz+2;z++)tiles.add(new int[]{x,z});
            tiles.sort(Comparator.comparingDouble(t->Math.hypot(t[0]-tx,t[1]-tz)));
            for(int[] t:tiles){long k=key(t[0],t[1]);PShape shape=details.get(k);
                if(shape==null&&made==0){CityScene patch=CityScene.window(this,(t[0]+.5f)*TILE,(t[1]+.5f)*TILE,TILE);shape=patch.createMesh(app);details.put(k,shape);made++;}
                if(shape!=null){app.pushMatrix();app.translate((t[0]+.5f)*TILE,0,(t[1]+.5f)*TILE);app.shape(shape);app.popMatrix();renderedDetailed++;}
            }
            while(details.size()>36){Iterator<Long> it=details.keySet().iterator();it.next();it.remove();}
        }
        app.popStyle();
    }
    private PShape skyline(PApplet app){
        // Distant tall-building bounding volumes; houses remain in the full footprint texture.
        PShape s=app.createShape();s.beginShape(PApplet.QUADS);s.noStroke();s.fill(224,230,235);
        for(Volume v:buildings)if(v.height>=25){float a=v.x0,b=v.x1,c=v.z0,d=v.z1,y=-v.height;
            float[][] q={{a,0,c,b,0,c,b,y,c,a,y,c},{b,0,c,b,0,d,b,y,d,b,y,c},{b,0,d,a,0,d,a,y,d,b,y,d},{a,0,d,a,0,c,a,y,c,a,y,d},{a,y,c,b,y,c,b,y,d,a,y,d}};
            for(float[] face:q)for(int i=0;i<12;i+=3)s.vertex(face[i],face[i+1],face[i+2]);
        }s.endShape();return s;
    }
}
