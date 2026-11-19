package com.rlclones.ai.brain;

import java.io.*;
import java.util.Locale;
import java.util.Random;

/** Bounded contextual bandit: one terminal recovery attempt, not a new strategic action/state. */
public final class RecoveryLearning {
    public static final int STATES=8, ACTIONS=4;
    private final float[] value=new float[STATES*ACTIONS];
    private final int[] samples=new int[STATES*ACTIONS], successes=new int[STATES*ACTIONS];
    public int choose(int state,int mask,Random random) {
        if(state<0||state>=STATES||(mask&15)==0) return -1;
        mask &= 15;
        int unseen=0;
        for(int a=0;a<ACTIONS;a++) if((mask&(1<<a))!=0&&samples[state*4+a]==0) unseen|=1<<a;
        if(unseen!=0) return randomAction(unseen,random);
        if(random.nextFloat()<0.1f) return randomAction(mask,random);
        int best=-1,ties=0;float q=-Float.MAX_VALUE;
        for(int a=0;a<ACTIONS;a++) if((mask&(1<<a))!=0) {
            float v=value[state*4+a];
            if(v>q+1e-6f) {q=v;best=a;ties=1;}
            else if(Math.abs(v-q)<=1e-6f&&random.nextInt(++ties)==0) best=a;
        }
        return best;
    }
    private static int randomAction(int mask,Random random) {
        int k=random.nextInt(Integer.bitCount(mask));
        for(int a=0;a<ACTIONS;a++) if((mask&(1<<a))!=0&&k--==0) return a;
        return -1;
    }
    public void record(int state,int action,float reward,boolean success) {
        if(state<0||state>=STATES||action<0||action>=ACTIONS||!Float.isFinite(reward)) return;
        int i=state*4+action;
        reward=Math.max(-2,Math.min(1,reward));
        if(samples[i]<Integer.MAX_VALUE) {
            int n=++samples[i];
            // Running mean permits exact sample-weighted sharing of independently collected experience.
            value[i]+=(reward-value[i])/n;
            if(success) successes[i]++;
        }
    }
    public static boolean succeeded(double moved,double progress,float damage,boolean exited) {
        return damage<=0&&moved>=0.3&&(progress>=0.35||exited);
    }
    public static float reward(double moved,double progress,float damage,int airLost,boolean exited) {
        if(!Double.isFinite(moved)||!Double.isFinite(progress)||!Float.isFinite(damage)) return Float.NaN;
        return Math.max(-2,(succeeded(moved,progress,damage,exited)?1f:moved<0.3?-0.6f:-0.1f)
                -Math.min(1.5f,Math.max(0,damage)*0.25f)-Math.min(0.5f,Math.max(0,airLost)/300f));
    }
    public float value(int state,int action) {return value[state*4+action];}
    public long trials() {long n=0;for(int count:samples)n+=count;return n;}
    public long wins() {long n=0;for(int count:successes)n+=count;return n;}
    public String report() {
        String[] names={"左","右","浮上","後退"};
        StringBuilder out=new StringBuilder("水中の立て直し: 試行="+trials()+" 成功="+wins());
        for(int a=0;a<4;a++) {long n=0,w=0;double sum=0;for(int s=0;s<8;s++){int i=s*4+a;n+=samples[i];w+=successes[i];sum+=(double)value[i]*samples[i];}
            if(n>0) out.append(String.format(Locale.ROOT," %s=%d/%d(評価%.2f)",names[a],w,n,sum/n));}
        return out.toString();
    }
    public byte[] save() {
        try {var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(1);
            for(int i=0;i<value.length;i++){out.writeInt(samples[i]);out.writeInt(successes[i]);out.writeFloat(value[i]);}return bytes.toByteArray();
        } catch(IOException e){throw new UncheckedIOException(e);}
    }
    public static RecoveryLearning load(byte[] bytes) {
        var result=new RecoveryLearning();
        if(bytes.length!=4+STATES*ACTIONS*12) return result;
        try {var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=1)return result;
            for(int i=0;i<result.value.length;i++){int n=in.readInt(),w=in.readInt();float q=in.readFloat();
                if(n<0||w<0||w>n||!Float.isFinite(q)||q < -2||q>1||n==0&&(w!=0||q!=0))return new RecoveryLearning();
                result.samples[i]=n;result.successes[i]=w;result.value[i]=q;}return result;
        } catch(IOException e){return new RecoveryLearning();}
    }
    public void merge(RecoveryLearning other) {
        if(other==this)return;
        for(int i=0;i<value.length;i++) {long n=(long)samples[i]+other.samples[i];if(n==0)continue;
            value[i]=(float)(((double)value[i]*samples[i]+(double)other.value[i]*other.samples[i])/n);
            long wins=(long)successes[i]+other.successes[i];
            samples[i]=(int)Math.min(Integer.MAX_VALUE,n);
            successes[i]=(int)Math.min(samples[i],Math.round((double)wins/n*samples[i]));}
    }
}
