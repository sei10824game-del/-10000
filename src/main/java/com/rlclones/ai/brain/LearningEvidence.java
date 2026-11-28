package com.rlclones.ai.brain;

import java.io.*;

/** Fixed-size hints (not rewards) and last 32 actual outcomes for four learning domains. */
public final class LearningEvidence {
    public static final int RECOVERY=0, ORE=1, WORKFLOW=2, ROLE=3, DOMAINS=4;
    private final int[][] hints=new int[DOMAINS][8];
    private final byte[][] outcomes=new byte[DOMAINS][32];
    private final int[] size=new int[DOMAINS],next=new int[DOMAINS];
    public static boolean valid(int domain,int state,int method){return domain>=0&&domain<DOMAINS&&state>=0&&state<8&&method>=0&&method<4;}
    public void suggest(int domain,int state,int method){if(valid(domain,state,method))hints[domain][state]|=1<<method;}
    public int suggested(int domain,int state){return domain>=0&&domain<DOMAINS&&state>=0&&state<8?hints[domain][state]:0;}
    public void record(int domain,boolean success){if(domain<0||domain>=DOMAINS)return;outcomes[domain][next[domain]]=(byte)(success?1:0);next[domain]=(next[domain]+1)%32;size[domain]=Math.min(32,size[domain]+1);}
    public String report(int domain){int recent=Math.min(16,size[domain]),previous=Math.max(0,size[domain]-16),r=0,p=0;
        for(int i=0;i<size[domain];i++){int result=outcomes[domain][(next[domain]-1-i+32)%32];if(i<16)r+=result;else p+=result;}
        return "直近="+r+"/"+recent+" 前期間="+p+"/"+previous;}
    public void mergeHints(LearningEvidence other){if(other==this)return;for(int d=0;d<DOMAINS;d++)for(int s=0;s<8;s++)hints[d][s]|=other.hints[d][s];}
    /** Histories of independent brains have no common time order; keep this brain's own window on merge. */
    public void copyHistoryFrom(LearningEvidence other){for(int d=0;d<DOMAINS;d++){System.arraycopy(other.outcomes[d],0,outcomes[d],0,32);size[d]=other.size[d];next[d]=other.next[d];}}
    public byte[] save(){
        var out=java.nio.ByteBuffer.allocate(4+DOMAINS*72);
        out.putInt(1);
        for(int d=0;d<DOMAINS;d++){
            for(int v:hints[d])out.putInt(v);
            out.putInt(size[d]).putInt(next[d]).put(outcomes[d]);
        }
        return out.array();
    }
    public static LearningEvidence load(byte[] bytes){var result=new LearningEvidence();if(bytes.length!=4+DOMAINS*72)return result;try{var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=1)return result;
        for(int d=0;d<DOMAINS;d++){for(int s=0;s<8;s++){int v=in.readInt();if(v<0||v>15)return new LearningEvidence();result.hints[d][s]=v;}result.size[d]=in.readInt();result.next[d]=in.readInt();if(result.size[d]<0||result.size[d]>32||result.next[d]<0||result.next[d]>=32)return new LearningEvidence();in.readFully(result.outcomes[d]);for(byte v:result.outcomes[d])if(v<0||v>1)return new LearningEvidence();}return result;
        }catch(IOException e){return new LearningEvidence();}}
}
