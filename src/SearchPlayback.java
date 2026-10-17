import java.util.*;

/** Incremental, exact six-neighbour A*: one real expansion per 50 ms of playback. */
final class SearchPlayback {
    interface Blocker {
        boolean blocked(int x,int y,int z);
        default boolean edgeBlocked(int x,int y,int z,int xx,int yy,int zz) { return false; }
    }
    static final int[][] DIR={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    static final class Node {
        final int x,y,z; int g=Integer.MAX_VALUE;Node parent;boolean closed;
        Node(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
        int[] cell(){return new int[]{x,y,z};}
    }
    static final class Entry {final Node node;final int g,f;Entry(Node n,int h){node=n;g=n.g;f=g+h;}}
    final int width,height,depth;final Blocker blocker;final int[] start,goal;
    final Map<Long,Node> nodes=new LinkedHashMap<>();
    final List<Node> expanded=new ArrayList<>();
    final List<int[]> rejected=new ArrayList<>();
    final PriorityQueue<Entry> open=new PriorityQueue<>(Comparator.comparingInt((Entry e)->e.f).thenComparingInt(e->-e.g));
    Node current; boolean done; String error;float elapsed;
    List<int[]> path=Collections.emptyList();
    SearchPlayback(int w,int h,int d,Blocker blocker,int[] start,int[] goal){
        width=w;height=h;depth=d;this.blocker=blocker;this.start=start.clone();this.goal=goal.clone();
        if(!inside(start[0],start[1],start[2])||!inside(goal[0],goal[1],goal[2])||blocker.blocked(start[0],start[1],start[2])||blocker.blocked(goal[0],goal[1],goal[2])){done=true;error="Blocked endpoint";return;}
        Node n=node(start[0],start[1],start[2]);n.g=0;open.add(new Entry(n,distance(n)));
    }
    void update(float dt){if(done)return;elapsed+=dt;if(elapsed>=.05f){elapsed%=.05f;step();}}
    void step(){
        if(done)return;
        Entry entry;
        do{entry=open.poll();}while(entry!=null&&(entry.node.closed||entry.g!=entry.node.g));
        if(entry==null){done=true;error="No route";return;}
        current=entry.node;current.closed=true;expanded.add(current);
        if(current.x==goal[0]&&current.y==goal[1]&&current.z==goal[2]){path=trace(current);done=true;return;}
        if(nodes.size()>250000){done=true;error="Search limit reached (250,000 cells)";return;}
        for(int[] d:DIR){int x=current.x+d[0],y=current.y+d[1],z=current.z+d[2];if(!inside(x,y,z))continue;
            if(blocker.blocked(x,y,z)||blocker.edgeBlocked(current.x,current.y,current.z,x,y,z)){if(rejected.size()<20000)rejected.add(new int[]{x,y,z});continue;}
            Node next=node(x,y,z);if(next.closed||current.g+1>=next.g)continue;
            next.g=current.g+1;next.parent=current;open.add(new Entry(next,distance(next)));
        }
    }
    private Node node(int x,int y,int z){long id=((long)x*height+y)*depth+z;return nodes.computeIfAbsent(id,k->new Node(x,y,z));}
    private boolean inside(int x,int y,int z){return x>=0&&x<width&&y>=0&&y<height&&z>=0&&z<depth;}
    private int distance(Node n){return Math.abs(n.x-goal[0])+Math.abs(n.y-goal[1])+Math.abs(n.z-goal[2]);}
    List<int[]> candidate(){return current==null?Collections.emptyList():trace(current);}
    private List<int[]> trace(Node n){List<int[]> result=new ArrayList<>();for(;n!=null;n=n.parent)result.add(n.cell());Collections.reverse(result);return result;}
}
