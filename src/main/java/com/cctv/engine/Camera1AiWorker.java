package com.cctv.engine;

import com.cctv.ai.YoloDetector;
import com.cctv.contract.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Per-camera CPU inference consumer. */
public final class Camera1AiWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Camera1AiWorker.class);
    private static final long IDLE_PARK_NANOS = 5_000_000L;
    private final long cameraId;
    private final FrameSource source;
    private final YoloDetector detector;
    private final EventBus events;
    private final ObjectTracker tracker;
    private final LineSource lineSource;
    private final LineCrossingDetector crossingDetector;
    private final ZoneSource zoneSource;
    private final ZoneIntrusionDetector zoneDetector;
    private final long intervalNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread worker;

    public Camera1AiWorker(long cameraId, FrameSource source, YoloDetector detector,
                           EventBus events, double aiRateFps) {
        this(cameraId, source, detector, events, aiRateFps, ignored -> List.of(), ignored -> List.of());
    }
    public Camera1AiWorker(long cameraId, FrameSource source, YoloDetector detector,
                           EventBus events, double aiRateFps, LineSource lineSource) {
        this(cameraId, source, detector, events, aiRateFps, lineSource, ignored -> List.of());
    }
    public Camera1AiWorker(long cameraId, FrameSource source, YoloDetector detector,
                           EventBus events, double aiRateFps, LineSource lineSource, ZoneSource zoneSource) {
        if (cameraId <= 0) throw new IllegalArgumentException("cameraId must be positive");
        if (!(aiRateFps > 0.0) || aiRateFps > 60.0 || !Double.isFinite(aiRateFps))
            throw new IllegalArgumentException("aiRateFps must be in (0, 60]");
        this.cameraId = cameraId;
        this.source = Objects.requireNonNull(source, "source");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.events = Objects.requireNonNull(events, "events");
        this.tracker = new ObjectTracker(cameraId);
        this.lineSource = Objects.requireNonNull(lineSource, "lineSource");
        this.crossingDetector = new LineCrossingDetector(cameraId);
        this.zoneSource = Objects.requireNonNull(zoneSource, "zoneSource");
        this.zoneDetector = new ZoneIntrusionDetector(cameraId);
        this.intervalNanos = Math.max(1L, (long)(1_000_000_000d / aiRateFps));
    }
    public synchronized void start() {
        if (closed.get()) throw new IllegalStateException("AI worker is closed");
        if (worker != null) return;
        worker = new Thread(this::runLoop, "camera-" + cameraId + "-ai");
        worker.setDaemon(true);
        worker.start();
    }
    public boolean isRunning() { Thread t=worker; return t!=null && t.isAlive() && !closed.get(); }
    private void runLoop() {
        long nextInferenceAt=0L, inferences=0L, published=0L;
        try {
            while (!closed.get()) {
                long now=System.nanoTime();
                if (now<nextInferenceAt) {
                    LockSupport.parkNanos(Math.min(IDLE_PARK_NANOS,nextInferenceAt-now));
                    if(Thread.interrupted()&&closed.get()) break;
                    continue;
                }
                Frame frame=source.next().orElse(null);
                if(frame==null) {
                    LockSupport.parkNanos(IDLE_PARK_NANOS);
                    if(Thread.interrupted()&&closed.get()) break;
                    continue;
                }
                nextInferenceAt=System.nanoTime()+intervalNanos;
                if(frame.cameraId()!=cameraId) {
                    log.warn("dropping frame for camera {} from camera {} AI worker",frame.cameraId(),cameraId);
                    continue;
                }
                try {
                    YoloDetector.Inference result=detector.detectTimed(frame.image(),cameraId,frame.capturedUtcMs());
                    inferences++;
                    for(Detection detection:result.detections()){events.publish(detection);published++;}
                    var tracks=tracker.update(frame.capturedUtcMs(),result.detections());
                    events.publish(new TrackingUpdate(cameraId,frame.capturedUtcMs(),tracks));
                    for(LineCrossingEvent crossing:crossingDetector.update(frame.capturedUtcMs(),lineSource.activeLines(cameraId),tracks))
                        events.publish(crossing);
                    for(ZoneDefinition zone:zoneDetector.update(frame.capturedUtcMs(),zoneSource.activeZones(cameraId),tracks))
                        events.publish(new IntrusionEvent(cameraId,zone,frame.capturedUtcMs(),0L,copy(frame.image())));
                    if(inferences==1||inferences%30==0)
                        log.info("camera {} AI: {} inferences, {} detections published; last {} ms",
                                cameraId,inferences,published,result.totalNanos()/1_000_000.0);
                } catch(RuntimeException failure) {
                    log.error("camera {} AI inference failed; worker will continue",cameraId,failure);
                }
            }
        } finally {
            detector.close();
            log.info("camera {} AI worker stopped after {} inferences / {} detections",cameraId,inferences,published);
        }
    }
    private static java.awt.image.BufferedImage copy(java.awt.image.BufferedImage source) {
        int type=source.getType()==0?java.awt.image.BufferedImage.TYPE_INT_ARGB:source.getType();
        var copy=new java.awt.image.BufferedImage(source.getWidth(),source.getHeight(),type);
        var g=copy.createGraphics();
        try { g.drawImage(source,0,0,null); } finally { g.dispose(); }
        return copy;
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        Thread t=worker;
        if(t==null){detector.close();return;}
        t.interrupt();
        try{t.join(5_000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(t.isAlive())log.warn("camera {} AI worker did not stop within 5 seconds",cameraId);
    }
}
