package com.cctv.engine;

import com.cctv.contract.ZoneDefinition;
import java.util.*;

/** Confirms zone entry from consecutive confirmed track observations. One detector per camera. */
public final class ZoneIntrusionDetector {
    public static final long DEFAULT_COOLDOWN_MS = 60_000L;
    private record Key(long zoneId, long trackId) { }
    private static final class State { int consecutiveInside; boolean alertedThisEntry; }
    private final long cameraId, cooldownMs;
    private final Map<Key, State> states = new HashMap<>();
    private final Map<Long, Long> lastAlertByZone = new HashMap<>();
    private long lastFrameUtcMs;
    public ZoneIntrusionDetector(long cameraId) { this(cameraId, DEFAULT_COOLDOWN_MS); }
    public ZoneIntrusionDetector(long cameraId, long cooldownMs) {
        if (cameraId <= 0 || cooldownMs < 0) throw new IllegalArgumentException("invalid detector configuration");
        this.cameraId=cameraId; this.cooldownMs=cooldownMs;
    }
    public synchronized List<ZoneDefinition> update(long frameUtcMs,List<ZoneDefinition> zones,List<TrackedObject> tracks) {
        if(frameUtcMs<=0||frameUtcMs<lastFrameUtcMs)throw new IllegalArgumentException("timestamps must be positive and monotonic");
        Objects.requireNonNull(zones,"zones"); Objects.requireNonNull(tracks,"tracks"); lastFrameUtcMs=frameUtcMs;
        Map<Long,ZoneDefinition> active=new HashMap<>();
        for(ZoneDefinition z:zones){if(z.cameraId()!=cameraId)throw new IllegalArgumentException("zone belongs to another camera");if(z.active())active.put(z.id(),z);}
        Set<Key> observed=new HashSet<>(); List<ZoneDefinition> entered=new ArrayList<>();
        for(TrackedObject t:tracks){
            if(t.cameraId()!=cameraId)throw new IllegalArgumentException("track belongs to another camera");
            if(!t.confirmed()||t.missedFrames()>0)continue;
            for(ZoneDefinition z:active.values()){
                Key k=new Key(z.id(),t.trackId());observed.add(k);State s=states.computeIfAbsent(k,x->new State());
                if(!z.contains(t.feetX(),t.feetY())){s.consecutiveInside=0;s.alertedThisEntry=false;continue;}
                s.consecutiveInside++;
                long lastAlert=lastAlertByZone.getOrDefault(z.id(),0L);
                if(s.consecutiveInside>=2&&!s.alertedThisEntry&&(lastAlert==0||frameUtcMs-lastAlert>=cooldownMs)){
                    entered.add(z);lastAlertByZone.put(z.id(),frameUtcMs);s.alertedThisEntry=true;
                } else if(s.consecutiveInside>=2) s.alertedThisEntry=true;
            }
        }
        states.keySet().removeIf(k->!observed.contains(k)||!active.containsKey(k.zoneId()));
        lastAlertByZone.keySet().removeIf(zoneId -> !active.containsKey(zoneId));
        return List.copyOf(entered);
    }
    public synchronized void reset(){states.clear();lastAlertByZone.clear();lastFrameUtcMs=0;}
}
