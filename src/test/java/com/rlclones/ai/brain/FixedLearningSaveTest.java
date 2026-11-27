package com.rlclones.ai.brain;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class FixedLearningSaveTest {
    @Test void recoveryMatchesLegacyBytes() throws Exception {
        RecoveryLearning r=new RecoveryLearning();
        r.record(2,3,1,true);r.record(2,3,-1,false);r.record(0,0,-2,false);
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(1);
        for(int s=0;s<8;s++)for(int a=0;a<4;a++){
            out.writeInt(s==2&&a==3?2:s==0&&a==0?1:0);
            out.writeInt(s==2&&a==3?1:0);
            out.writeFloat(s==0&&a==0?-2f:0f);
        }
        out.flush();
        assertArrayEquals(bytes.toByteArray(),r.save());
        assertArrayEquals(r.save(),RecoveryLearning.load(r.save()).save());
    }
    @Test void evidenceMatchesLegacyBytesAfterWrapping() throws Exception {
        LearningEvidence e=new LearningEvidence();
        e.suggest(1,4,2);
        for(int i=0;i<70;i++)e.record(1,i%3==0);
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(1);
        for(int d=0;d<4;d++){
            for(int s=0;s<8;s++)out.writeInt(d==1&&s==4?4:0);
            out.writeInt(d==1?32:0);out.writeInt(d==1?70%32:0);
            byte[] ring=new byte[32];
            if(d==1)for(int i=0;i<70;i++)ring[i%32]=(byte)(i%3==0?1:0);
            out.write(ring);
        }
        out.flush();
        assertArrayEquals(bytes.toByteArray(),e.save());
        assertArrayEquals(e.save(),LearningEvidence.load(e.save()).save());
    }
}
