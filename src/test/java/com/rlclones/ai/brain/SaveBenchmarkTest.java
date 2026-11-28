package com.rlclones.ai.brain;

import java.io.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SaveBenchmarkTest {
    private static volatile int sink;
    private static byte[] legacy(QTable t) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(t.actions());out.writeInt(t.size());
        for(var row:t.entries()){
            var e=row.getValue();out.writeInt(row.getIntKey());out.writeInt(e.visits);
            for(int a=0;a<t.actions();a++){out.writeFloat(e.q[a]);out.writeInt(e.n[a]);out.writeInt(e.demo[a]);}
        }
        out.flush();return bytes.toByteArray();
    }
    private static QTable table(int states,int actions){
        QTable t=new QTable(actions);Random r=new Random(64);
        for(int i=0;i<states;i++){
            var e=t.get(i,QTable.Prior.ZERO);e.visits=r.nextInt(10000);
            for(int a=0;a<actions;a++){e.q[a]=r.nextFloat()*10-5;e.n[a]=r.nextInt(10000);e.demo[a]=r.nextInt(100);}
        }
        var e=t.peek(0);e.q[0]=Float.intBitsToFloat(0x7fa12345);e.q[1]=-0f;e.q[2]=Float.POSITIVE_INFINITY;
        return t;
    }
    private interface Encoder {byte[] encode() throws Exception;}
    private static long[] measure(Encoder f,int repeats) throws Exception {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long tid=Thread.currentThread().getId();
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        long bytes=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
        for(int i=0;i<repeats;i++){byte[] b=f.encode();sink^=b.length+b[b.length-1];}
        return new long[]{System.nanoTime()-start,bean.getThreadAllocatedBytes(tid)-bytes};
    }
    @Test void exactBytesAndDirectPerformance() throws Exception {
        for(int states:new int[]{128,1024,4096})for(int actions:new int[]{15,30}){
            QTable t=table(states,actions);
            assertArrayEquals(legacy(t),t.toBytes());
            int repeats=Math.max(40,200000/states);
            for(int i=0;i<3;i++){measure(()->legacy(t),repeats/4);measure(t::toBytes,repeats/4);}
            var old=new long[7];var fresh=new long[7];var oldBytes=new long[7];var freshBytes=new long[7];
            for(int i=0;i<7;i++){
                long[] a,b;if(i%2==0){a=measure(()->legacy(t),repeats);b=measure(t::toBytes,repeats);}else{b=measure(t::toBytes,repeats);a=measure(()->legacy(t),repeats);}
                old[i]=a[0]/repeats;fresh[i]=b[0]/repeats;oldBytes[i]=a[1]/repeats;freshBytes[i]=b[1]/repeats;
            }
            Arrays.sort(old);Arrays.sort(fresh);Arrays.sort(oldBytes);Arrays.sort(freshBytes);
            String line=String.format(Locale.ROOT,"SAVE-BENCH states=%d actions=%d legacy_ns=%d new_ns=%d legacy_alloc=%d new_alloc=%d",states,actions,old[3],fresh[3],oldBytes[3],freshBytes[3]);
            System.out.println(line);
            String dest=System.getenv("RL_SAVE_BENCH_OUTPUT");
            if(dest!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(dest),line+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
        }
    }
}
