import com.rlclones.ai.observe.AgentEvents;
import java.util.*;
import java.lang.management.ManagementFactory;
/** Standalone replay: compares exact ordered event results before timing. */
public class EventIntervalBenchmark {
    static volatile long sink;
    static final int N=300, Q=300000;
    static final UUID[] ids=new UUID[N];
    static final int[] agents=new int[Q];
    static final long[] from=new long[Q],to=new long[Q];
    static void setup(int emptyPercent) {
        AgentEvents.clear(); Random r=new Random(1729);
        for(int a=0;a<N;a++){ids[a]=new UUID(0,a);for(int i=0;i<80;i++)AgentEvents.record(ids[a],i,AgentEvents.Kind.WORK_SUCCESS,i,a,i%4);}
        for(int i=0;i<Q;i++){agents[i]=r.nextInt(N);boolean empty=r.nextInt(100)<emptyPercent;from[i]=empty?80+r.nextInt(100):r.nextInt(80);to[i]=from[i]+1+r.nextInt(20);}
    }
    static long run(){long hash=0;for(int i=0;i<Q;i++){var events=AgentEvents.sharedBetween(ids[agents[i]],from[i],to[i]);hash=31*hash+events.size();for(var e:events)hash=31*hash+e.tick();}return hash;}
    public static void main(String[] args){
        var mx=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();mx.setThreadAllocatedMemoryEnabled(true);
        for(int empty:new int[]{0,50,90,100}){
            setup(empty);
            for(int i=0;i<Q;i++)if(!AgentEvents.between(ids[agents[i]],from[i],to[i]).equals(AgentEvents.sharedBetween(ids[agents[i]],from[i],to[i])))throw new AssertionError(i);
            for(int i=0;i<4;i++)sink=run();
            double[] ns=new double[5],bytes=new double[5];long hash=0;
            for(int i=0;i<5;i++){long b=mx.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();hash=run();ns[i]=(System.nanoTime()-start)/(double)Q;bytes[i]=(mx.getThreadAllocatedBytes(Thread.currentThread().getId())-b)/(double)Q;sink=hash;}
            Arrays.sort(ns);Arrays.sort(bytes);System.out.printf(Locale.ROOT,"empty=%d ns=%.2f bytes=%.2f hash=%d%n",empty,ns[2],bytes[2],hash);
        }
    }
}
