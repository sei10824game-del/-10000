package com.rlclones.ai.brain;

import com.rlclones.ai.combat.CombatAction;
import com.rlclones.ai.combat.CombatState;
import com.rlclones.ai.strategy.Option;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same test source runs in the baseline and candidate worktrees; compare its complete trace digest. */
class SeededLearningComparisonTest {
    private static Random seed(Brain b,long seed) throws Exception {
        var field=Brain.class.getDeclaredField("random");field.setAccessible(true);
        Random r=(Random)field.get(b);r.setSeed(seed);return r;
    }
    private static int mask(int count){return count==32?-1:(1<<count)-1;}
    private static void table(DataOutputStream out,QTable t) throws IOException {
        var keys=new ArrayList<Integer>();
        for(var e:t.entries())keys.add(e.getIntKey());
        Collections.sort(keys);out.writeInt(keys.size());
        for(int key:keys){
            var e=t.peek(key);out.writeInt(key);out.writeInt(e.visits);
            for(int a=0;a<t.actions();a++){out.writeInt(Float.floatToIntBits(e.q[a]));out.writeInt(e.n[a]);out.writeInt(e.demo[a]);}
        }
    }
    private static String run(long value) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){
            Brain b=new Brain();Random policy=seed(b,value);Random input=new Random(value^0x53abcL);
            var types=new String[]{"minecraft:zombie","minecraft:skeleton","minecraft:creeper","mod:unknown"};
            b.knowledge(types[1]).trait(EnemyKnowledge.RANGED);
            b.knowledge(types[2]).trait(EnemyKnowledge.EXPLOSIVE);
            for(int i=0;i<24000;i++){
                boolean strategy=i%5==0;
                String type=strategy?Brain.STRATEGY:types[input.nextInt(types.length)];
                int n=strategy?Option.COUNT:CombatAction.COUNT;
                int limit=strategy?3456:CombatState.SIZE;
                int state=input.nextInt(Math.min(limit,128)),next=input.nextBoolean()?state:input.nextInt(Math.min(limit,128));
                int m=input.nextInt()&mask(n);if(m==0)m=1;
                int action=strategy?b.chooseStrategy(state,m):b.chooseCombat(type,state,m);
                assertTrue(action>=0&&(m&(1<<action))!=0);
                out.writeInt(action);
                if(i%3==0)b.recordDemo(type,state,action);
                b.learn(type,state,action,(input.nextInt(65)-32)*.125f,next,i%7==0,.9f,i%4==0?0:m,i%3==0?.8f:1f,i%3==0);
                if(i%8==0)b.replaySome(17);
                if(i%17==0)b.knowledge(types[3]).trait(EnemyKnowledge.RANGED);
                if(i%23==0)b.learnItemUse("minecraft:iron_sword",input.nextInt(4),input.nextFloat());
                // Verify every changed state's float bits, visit counts, and demo counts.
                QTable t=strategy?b.strategyTable():b.combatTable(type);
                for(int key:new int[]{state,next}){
                    var e=t.peek(key);out.writeBoolean(e!=null);
                    if(e!=null){out.writeInt(e.visits);for(int a=0;a<n;a++){out.writeInt(Float.floatToIntBits(e.q[a]));out.writeInt(e.n[a]);out.writeInt(e.demo[a]);}}
                }
                if(i%1000==0){
                    table(out,b.strategyTable());
                    for(String k:new TreeSet<>(b.combatTypes())){out.writeUTF(k);table(out,b.combatTable(k));}
                    var tag=b.save();byte[] replay=tag.getByteArray("replay");out.writeInt(replay.length);out.write(replay);
                    out.writeLong(b.ownUpdates);out.writeLong(b.imitationUpdates);out.writeLong(b.replayUpdates);out.writeLong(b.pendingReplay());
                    out.writeLong(policy.nextLong());
                }
            }
            table(out,b.strategyTable());
            for(String k:new TreeSet<>(b.combatTypes())){out.writeUTF(k);table(out,b.combatTable(k));}
            var replay=b.save().getByteArray("replay");out.writeInt(replay.length);out.write(replay);
            out.writeLong(policy.nextLong());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    @org.junit.jupiter.api.BeforeAll static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void identicalInputAndPolicySeedProduceComparableTrace() throws Exception {
        StringBuilder result=new StringBuilder();
        for(long s:new long[]{81,92177,660911}){
            String hash=run(s);assertEquals(hash,run(s),"local repeat must itself be deterministic");
            result.append(s).append(' ').append(hash).append('\n');
        }
        String dest=System.getenv("RL_EXACT_OUTPUT");
        if(dest!=null&&!dest.isBlank())Files.writeString(Path.of(dest),result.toString());
        System.out.print(result);
    }
}
