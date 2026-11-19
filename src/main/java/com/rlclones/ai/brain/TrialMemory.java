package com.rlclones.ai.brain;

import net.minecraft.nbt.*;
import java.util.*;

/** Small empirical item log. Capacity exhaustion stops new experiments rather than forgetting harmful evidence. */
public final class TrialMemory {
    public static final int LIMIT=128;
    private static final class Entry {int n;float mean;boolean harmful;}
    private final Map<String,Entry> items=new LinkedHashMap<>();
    public boolean known(String key){return items.containsKey(key);}
    public boolean room(String key){return items.containsKey(key)||items.size()<LIMIT;}
    public boolean harmful(String key){var entry=items.get(key);return entry!=null&&entry.harmful;}
    public int size(){return items.size();}
    public void record(String key,float reward,boolean harmful){if(key==null||key.isEmpty()||key.length()>260||!Float.isFinite(reward)||!room(key))return;
        var e=items.computeIfAbsent(key,k->new Entry());if(e.n<Integer.MAX_VALUE){e.n++;e.mean+=(Math.max(-2,Math.min(1,reward))-e.mean)/e.n;}e.harmful|=harmful;}
    public String report(){int bad=0,food=0,gear=0;for(var entry:items.entrySet()){if(entry.getValue().harmful)bad++;if(entry.getKey().startsWith("food|"))food++;else gear++;}
        return "品物の試用: 食料="+food+" 装備・道具="+gear+" 有害な変化を観測="+bad+" 保存="+items.size()+"/"+LIMIT;}
    public List<String> details(int requestedPage){
        var sorted=new ArrayList<>(items.entrySet());
        sorted.sort(Comparator.<Map.Entry<String,Entry>,Boolean>comparing(e->!e.getValue().harmful).thenComparing(Map.Entry::getKey));
        int pages=Math.max(1,(sorted.size()+7)/8),page=Math.max(1,Math.min(pages,requestedPage));
        List<String> lines=new ArrayList<>();lines.add(report()+" ページ="+page+"/"+pages);
        for(int i=(page-1)*8;i<Math.min(sorted.size(),page*8);i++){var e=sorted.get(i);lines.add(String.format(java.util.Locale.ROOT,"%s 観測=%d 評価=%.2f 有害な変化=%s",e.getKey(),e.getValue().n,e.getValue().mean,e.getValue().harmful));}
        return lines;
    }
    public CompoundTag save(){var tag=new CompoundTag();var entries=new ListTag();for(var entry:items.entrySet()){var row=new CompoundTag();row.putString("id",entry.getKey());row.putInt("n",entry.getValue().n);row.putFloat("q",entry.getValue().mean);row.putBoolean("harmful",entry.getValue().harmful);entries.add(row);}tag.put("entries",entries);return tag;}
    public static TrialMemory load(CompoundTag tag){var memory=new TrialMemory();var list=tag.getList("entries",Tag.TAG_COMPOUND);for(int i=0;i<Math.min(LIMIT,list.size());i++){var row=list.getCompound(i);String key=row.getString("id");int n=row.getInt("n");float value=row.getFloat("q");if(n<1||!Float.isFinite(value)||value < -2||value>1||key.isEmpty()||key.length()>260)continue;
        var e=new Entry();e.n=n;e.mean=value;e.harmful=row.getBoolean("harmful");memory.items.putIfAbsent(key,e);}return memory;}
    public void merge(TrialMemory other){if(other==this)return;for(var entry:other.items.entrySet()){var src=entry.getValue();if(!room(entry.getKey())){if(!src.harmful)continue;String safe=null;for(var own:items.entrySet())if(!own.getValue().harmful){safe=own.getKey();break;}if(safe==null)continue;items.remove(safe);}var dst=items.computeIfAbsent(entry.getKey(),k->new Entry());long n=(long)dst.n+src.n;dst.mean=(float)(((double)dst.mean*dst.n+(double)src.mean*src.n)/n);dst.n=(int)Math.min(Integer.MAX_VALUE,n);dst.harmful|=src.harmful;}}
}
