package com.rlclones.ai.brain;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class QTableTest {
    private static int oldArgmax(QTable.Entry e,int mask,java.util.Random random){
        int best=-1,ties=0;float bestQ=Float.NEGATIVE_INFINITY;
        for(int a=0;a<e.q.length;a++){
            if((mask&(1<<a))==0)continue;
            float q=e.q[a];
            if(q>bestQ+1e-6f){bestQ=q;best=a;ties=1;}
            else if(Math.abs(q-bestQ)<=1e-6f){ties++;if(random.nextInt(ties)==0)best=a;}
        }
        return best;
    }
    @Test void maskedScanPreservesEveryDecisionAndRandomDraw(){
        var data=new java.util.Random(54029);
        var oldRandom=new java.util.Random(8142);
        var newRandom=new java.util.Random(8142);
        for(int count=1;count<=32;count++){
            var e=new QTable(count).get(1,QTable.Prior.ZERO);
            for(int i=0;i<4096;i++){
                for(int a=0;a<count;a++)e.q[a]=switch(data.nextInt(9)){
                    case 0->Float.NaN;case 1->Float.NEGATIVE_INFINITY;case 2->Float.POSITIVE_INFINITY;
                    default->data.nextInt(5)*.0000005f;
                };
                int mask=data.nextInt();
                float best=Float.NEGATIVE_INFINITY;
                for(int a=0;a<count;a++)if((mask&(1<<a))!=0&&e.q[a]>best)best=e.q[a];
                assertEquals(Float.floatToIntBits(best==Float.NEGATIVE_INFINITY?0:best),Float.floatToIntBits(QTable.max(e,mask)));
                assertEquals(oldArgmax(e,mask,oldRandom),QTable.argmax(e,mask,newRandom));
                assertEquals(oldRandom.nextLong(),newRandom.nextLong());
            }
        }
    }
    @Test void serializationMatchesOriginalByteFormatAndIterationOrder() throws Exception {
        QTable table=new QTable(15);
        var random=new java.util.Random(173);
        for(int i=0;i<2048;i++){
            var e=table.get(random.nextInt(),QTable.Prior.ZERO);e.visits=i;
            for(int a=0;a<15;a++){e.q[a]=random.nextFloat();e.n[a]=i+a;e.demo[a]=a;}
        }
        var buffer=new java.io.ByteArrayOutputStream();
        var out=new java.io.DataOutputStream(buffer);
        out.writeInt(table.actions());out.writeInt(table.size());
        for(var entry:table.entries()){
            var e=entry.getValue();out.writeInt(entry.getIntKey());out.writeInt(e.visits);
            for(int a=0;a<15;a++){out.writeFloat(e.q[a]);out.writeInt(e.n[a]);out.writeInt(e.demo[a]);}
        }
        out.flush();assertArrayEquals(buffer.toByteArray(),table.toBytes());
    }

    @Test
    void mergeKeepsWeightedValuesWhenCountsOverflow() {
        QTable a = new QTable(2);
        QTable b = new QTable(2);
        QTable.Entry first = a.get(7, QTable.Prior.ZERO);
        QTable.Entry second = b.get(7, QTable.Prior.ZERO);
        first.q[0] = 6f;
        second.q[0] = 2f;
        first.n[0] = second.n[0] = Integer.MAX_VALUE;
        first.visits = second.visits = Integer.MAX_VALUE;
        a.mergeFrom(b);
        assertEquals(4f, first.q[0]);
        assertEquals(Integer.MAX_VALUE, first.n[0]);
        assertEquals(Integer.MAX_VALUE, first.visits);
        a.mergeFrom(a);
        assertEquals(4f, first.q[0]);
    }

    @Test
    void roundTripRetainsValuesAndAdaptsActionCounts() {
        QTable original = new QTable(2);
        QTable.Entry entry = original.get(42, QTable.Prior.ZERO);
        entry.q[0] = -3f;
        entry.q[1] = 9f;
        entry.n[1] = 12;
        entry.demo[1] = 5;
        entry.visits = 17;
        QTable loaded = QTable.fromBytes(original.toBytes(), 3);
        assertArrayEquals(new float[]{-3f, 9f, 0f}, loaded.peek(42).q);
        assertEquals(12, loaded.peek(42).n[1]);
        assertEquals(5, loaded.peek(42).demo[1]);
        assertEquals(17, loaded.peek(42).visits);
        assertEquals(-3f, QTable.fromBytes(original.toBytes(), 1).peek(42).q[0]);
    }

    @Test
    void invalidDimensionsAndTruncationReturnEmptyTable() {
        for (int actions : new int[]{-1, 0, 33, Integer.MAX_VALUE}) {
            byte[] bytes = ByteBuffer.allocate(8).putInt(actions).putInt(1).array();
            assertEquals(0, QTable.fromBytes(bytes, 2).size());
        }
        QTable valid = new QTable(2);
        valid.get(1, QTable.Prior.ZERO);
        byte[] bytes = valid.toBytes();
        for (int length = 0; length < bytes.length; length++) {
            assertEquals(0, QTable.fromBytes(java.util.Arrays.copyOf(bytes, length), 2).size());
        }
    }

    @Test
    void invalidNumbersCannotPoisonLoadedPolicy() {
        byte[] bytes = ByteBuffer.allocate(28).putInt(1).putInt(1)
                .putInt(3).putInt(-2).putFloat(Float.NaN).putInt(-4).putInt(-5).array();
        QTable.Entry entry = QTable.fromBytes(bytes, 1).peek(3);
        assertEquals(0f, entry.q[0]);
        assertEquals(0, entry.n[0]);
        assertEquals(0, entry.demo[0]);
        assertEquals(0, entry.visits);
    }

    @Test
    void dimensionsAreCheckedBeforeMerge() {
        assertThrows(IllegalArgumentException.class, () -> new QTable(0));
        assertThrows(IllegalArgumentException.class, () -> new QTable(33));
        assertThrows(IllegalArgumentException.class, () -> new QTable(2).mergeFrom(new QTable(3)));
    }
}
