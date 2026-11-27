package com.rlclones.ai.brain;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayExactTest {
    @Test void ringAndSaveMatchReferenceAcrossWraps() throws Exception {
        for(int capacity:new int[]{1,2,3,17,128,257}){
            ReplayBuffer actual=new ReplayBuffer(capacity);
            var physical=new int[capacity];int next=0,size=0;
            var history=new ArrayDeque<Integer>();
            var a=new Random(87);var b=new Random(87);
            for(int i=0;i<capacity*5+7;i++){
                actual.add("enemy:"+i,i,i%15,i*.125f,i+1,false,.9f,15,1f);
                physical[next]=i;next=(next+1)%capacity;size=Math.min(size+1,capacity);
                history.addLast(i);if(history.size()>capacity)history.removeFirst();
                assertEquals(physical[a.nextInt(size)],actual.sample(b).s);
                var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
                out.writeInt(size);
                for(int j:history){
                    out.writeUTF("enemy:"+j);out.writeInt(j);out.writeByte(j%15);out.writeFloat(j*.125f);
                    out.writeInt(j+1);out.writeBoolean(false);out.writeFloat(.9f);out.writeInt(15);out.writeFloat(1f);
                }
                out.flush();assertArrayEquals(bytes.toByteArray(),actual.toBytes());
            }
        }
    }
}
