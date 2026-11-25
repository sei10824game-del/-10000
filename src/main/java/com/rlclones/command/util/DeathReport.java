package com.rlclones.command.util;

import java.util.*;

/** Read-only views of the existing cumulative death combinations; no fabricated chronology. */
public final class DeathReport {
    private DeathReport() {}
    public enum View {SUMMARY, MOBS, ACTIONS, DETAILS}
    public static final int PAGE_SIZE=8;
    public record Row(String label,long count) {}
    public record Page(long total,int number,int pages,int groups,List<Row> rows) {}
    public static Page page(Map<String,Integer> source,View view,int requested) {
        Map<String,Long> grouped=new HashMap<>();long total=0;
        for(var entry:new HashMap<>(source).entrySet()){
            if(entry.getKey()==null||entry.getValue()==null||entry.getValue()<=0)continue;
            String raw=entry.getKey();int during=raw.lastIndexOf(" during ");
            String causeAndMob=during<0?raw:raw.substring(0,during),action=during<0?"unknown":raw.substring(during+8);
            int by=causeAndMob.indexOf(" by ");String cause=by<0?causeAndMob:causeAndMob.substring(0,by),mob=by<0?"環境・不明":causeAndMob.substring(by+4);
            String key=switch(view){case SUMMARY->cause;case MOBS->mob;case ACTIONS->action.equals("null")?"行動なし":action;case DETAILS->raw;};
            long count=entry.getValue();total+=count;grouped.merge(key,count,Long::sum);
        }
        var rows=new ArrayList<Row>();grouped.forEach((key,count)->rows.add(new Row(key,count)));
        rows.sort(Comparator.comparingLong(Row::count).reversed().thenComparing(Row::label));
        int pages=Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE),number=Math.max(1,Math.min(pages,requested));
        int start=(number-1)*PAGE_SIZE;
        return new Page(total,number,pages,rows.size(),List.copyOf(rows.subList(start,Math.min(rows.size(),start+PAGE_SIZE))));
    }
}
