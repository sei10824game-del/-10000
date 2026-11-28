import com.rlclones.ai.brain.*;
import java.util.*;
import java.lang.reflect.*;
public class ReplayExactCheck {
 static long samples=0,saves=0,additions=0;
 static void check(boolean b){if(!b)throw new AssertionError();}
 static String value(OldReplayBuffer.Transition t){return t.table+":"+t.s+":"+t.a+":"+Float.floatToIntBits(t.r)+":"+t.s2+":"+t.terminal+":"+Float.floatToIntBits(t.gamma)+":"+t.nextMask+":"+Float.floatToIntBits(t.weight);}
 static String value(ReplayBuffer.Transition t){return t.table+":"+t.s+":"+t.a+":"+Float.floatToIntBits(t.r)+":"+t.s2+":"+t.terminal+":"+Float.floatToIntBits(t.gamma)+":"+t.nextMask+":"+Float.floatToIntBits(t.weight);}
 public static void main(String[] a)throws Exception{
 for(int cap:new int[]{1,3,16,17,31,32,33,100,4096}){
  var old=new OldReplayBuffer(cap);var cur=new ReplayBuffer(cap);var r=new Random(42);var x=new Random(101);var y=new Random(101);
  for(int i=0;i<cap*3+33;i++){
   String table=i%2==0?"strategy":"combat:ゾンビ";int s=r.nextInt(),act=r.nextInt(32),s2=r.nextInt(),mask=r.nextInt();float reward=r.nextFloat()*20-10,gamma=r.nextFloat(),weight=r.nextFloat()+.01f;boolean terminal=r.nextBoolean();
   old.add(table,s,act,reward,s2,terminal,gamma,mask,weight);cur.add(table,s,act,reward,s2,terminal,gamma,mask,weight);additions++;
   check(old.size()==cur.size());for(int k=0;k<4;k++){check(value(old.sample(x)).equals(value(cur.sample(y))));samples++;}
   if(i%127==0||i==cap-1||i==cap||i==cap*3+32){byte[] b=old.toBytes(),c=cur.toBytes();check(Arrays.equals(b,c));saves++;var o2=new OldReplayBuffer(cap);var n2=new ReplayBuffer(cap);o2.readFrom(c);n2.readFrom(b);check(Arrays.equals(o2.toBytes(),n2.toBytes()));saves++;}
  }
 }
 Field of=OldReplayBuffer.class.getDeclaredField("items"),nf=ReplayBuffer.class.getDeclaredField("items");of.setAccessible(true);nf.setAccessible(true);
 System.out.printf("additions=%d samples=%d save_checks=%d%n",additions,samples,saves);
 for(int n:new int[]{0,16,100,512,1024,2048,4096}){var o=new OldReplayBuffer(4096);var c=new ReplayBuffer(4096);for(int i=0;i<n;i++){o.add("a",i,0,1,2,false,.9f,1,1);c.add("a",i,0,1,2,false,.9f,1,1);}System.out.printf("experience=%d old_slots=%d new_slots=%d%n",n,((Object[])of.get(o)).length,((Object[])nf.get(c)).length);}
 }
}
