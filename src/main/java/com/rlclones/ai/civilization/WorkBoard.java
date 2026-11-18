package com.rlclones.ai.civilization;

import java.util.*;

/** Server-thread job board. Requests are re-announced after restart; no offline worker owns a job. */
public final class WorkBoard {
    public static final String FOOD = "#food";
    public static final long LIFETIME = 2400, LEASE = 100;
    public record Key(UUID requester, String dimension, String team, String item) {}
    public static final class Job {
        public final Key key;
        public int count;
        public long updated;
        private UUID worker;
        private long leaseUntil;
        private Job(Key key, int count, long now) { this.key = key; this.count = count; updated = now; }
        public UUID worker(long now) { return now < leaseUntil ? worker : null; }
    }
    private final Map<Key, Job> jobs = new LinkedHashMap<>();
    public boolean post(Key key, int count, long now) {
        Objects.requireNonNull(key);
        if (count < 1 || count > 64) return false;
        prune(now);
        Job old = jobs.get(key);
        if (old != null) { old.count = count; old.updated = now; return true; }
        if (jobs.size() >= 256) return false;
        jobs.put(key, new Job(key, count, now));
        return true;
    }
    public Job find(Key key, long now) {
        Job job = jobs.get(key);
        if (job != null && now - job.updated >= LIFETIME) { jobs.remove(key); return null; }
        return job;
    }
    public List<Job> jobs(long now) { prune(now); return List.copyOf(jobs.values()); }
    public boolean claim(Key key, UUID worker, long now) {
        prune(now);
        Job job = jobs.get(key);
        if (job == null || key.requester.equals(worker)) return false;
        UUID owner = job.worker(now);
        if (owner != null && !owner.equals(worker)) return false;
        job.worker = worker; job.leaseUntil = now + LEASE;
        return true;
    }
    public void release(Key key, UUID worker) {
        Job job = jobs.get(key);
        if (job != null && worker.equals(job.worker)) { job.worker = null; job.leaseUntil = 0; }
    }
    public boolean complete(Key key, UUID worker, long now) {
        Job job = jobs.get(key);
        return job != null && worker.equals(job.worker(now)) && jobs.remove(key, job);
    }
    public void cancel(Key key) { jobs.remove(key); }
    private void prune(long now) { jobs.values().removeIf(job -> now - job.updated >= LIFETIME); }
}
