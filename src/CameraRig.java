import java.util.*;
import processing.core.*;

/** Frame-rate independent free camera: WASD ground plane, QE height, arrows horizontal angle/tilt. */
final class CameraRig {
    final Set<Integer> held=new HashSet<>();
    float x=0,y=-900,z=1100,yaw=0,pitch=-.6f;
    boolean bird;
    float birdHeight=1800;
    void resetLocal(float cx,float cz){x=cx;y=-900;z=cz+1100;yaw=0;pitch=-.6f;bird=false;}
    void overview(TorontoAtlas atlas){x=(atlas.minX+atlas.maxX)/2;z=(atlas.minZ+atlas.maxZ)/2;y=-38000;bird=true;birdHeight=38000;}
    void route(float ax,float az,float bx,float bz){x=(ax+bx)/2;z=(az+bz)/2;birdHeight=Math.max(900,(float)Math.hypot(ax-bx,az-bz)*1.35f);y=-birdHeight;bird=true;}
    void update(float dt){
        float speed=Math.max(80,Math.min(7000,Math.abs(y)*.6f));if(held.contains(16))speed*=3;
        float forward=(held.contains((int)'W')?1:0)-(held.contains((int)'S')?1:0);
        float right=(held.contains((int)'D')?1:0)-(held.contains((int)'A')?1:0);
        x+=(float)(Math.sin(yaw)*forward+Math.cos(yaw)*right)*speed*dt;
        z+=(float)(-Math.cos(yaw)*forward+Math.sin(yaw)*right)*speed*dt;
        float turn=(held.contains(PApplet.RIGHT)?1:0)-(held.contains(PApplet.LEFT)?1:0);
        if(turn!=0){yaw+=turn*dt;bird=false;}
        y+=((held.contains((int)'Q')?1:0)-(held.contains((int)'E')?1:0))*speed*dt;
        y=Math.max(-60000,Math.min(-8,y));
        if(held.contains(PApplet.UP)){pitch=Math.min(.9f,pitch+dt);bird=false;}
        if(held.contains(PApplet.DOWN)){pitch=Math.max(-1.5f,pitch-dt);bird=false;}
    }
    PVector focus(){if(bird)return new PVector(x,0,z);float distance=Math.min(5000,Math.max(200,-y/(float)Math.max(.1,-Math.sin(pitch))));return new PVector(x+(float)Math.sin(yaw)*distance*(float)Math.cos(pitch),y+(float)Math.sin(pitch)*-distance,z-(float)Math.cos(yaw)*distance*(float)Math.cos(pitch));}
    void apply(PApplet app){
        app.perspective(PApplet.PI/3f,(float)app.width/app.height,2,120000);
        if(bird)app.camera(x,y,z,x,0,z,0,0,-1);
        else app.camera(x,y,z,x+(float)Math.sin(yaw)*(float)Math.cos(pitch),y-(float)Math.sin(pitch),z-(float)Math.cos(yaw)*(float)Math.cos(pitch),0,1,0);
    }
    void drag(float dx,float dy){bird=false;yaw+=dx*.005f;pitch=Math.max(-1.5f,Math.min(.9f,pitch-dy*.005f));}
}
