package com.cctv.engine;

import com.cctv.contract.ZoneDefinition;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ZoneIntrusionDetectorTest {
    private static final ZoneDefinition ZONE = new ZoneDefinition(1,1,"Door",0.2,0.2,0.6,0.6,true);
    private static TrackedObject track(long id,double x,double y,boolean confirmed,int missed) {
        return new TrackedObject(1,id,"person",0.95f,x-0.05,y-0.1,x+0.05,y,1000,1000,2,missed,confirmed);
    }
    @Test void requiresTwoConsecutiveConfirmedObservationsAndEmitsOncePerEntry() {
        var d=new ZoneIntrusionDetector(1,0);
        assertTrue(d.update(1000,List.of(ZONE),List.of(track(4,.5,.5,false,0))).isEmpty());
        assertTrue(d.update(1100,List.of(ZONE),List.of(track(4,.5,.5,true,0))).isEmpty());
        assertEquals(List.of(ZONE),d.update(1200,List.of(ZONE),List.of(track(4,.5,.5,true,0))));
        assertTrue(d.update(1300,List.of(ZONE),List.of(track(4,.5,.5,true,0))).isEmpty());
    }
    @Test void exitAndReentryCanAlertAgain() {
        var d=new ZoneIntrusionDetector(1,0);
        d.update(1000,List.of(ZONE),List.of(track(4,.5,.5,true,0)));
        assertEquals(1,d.update(1100,List.of(ZONE),List.of(track(4,.5,.5,true,0))).size());
        d.update(1200,List.of(ZONE),List.of(track(4,.05,.5,true,0)));
        d.update(1300,List.of(ZONE),List.of(track(4,.5,.5,true,0)));
        assertEquals(1,d.update(1400,List.of(ZONE),List.of(track(4,.5,.5,true,0))).size());
    }
    @Test void inactiveZonesAndMissedTracksDoNotAlert() {
        var d=new ZoneIntrusionDetector(1,0);
        var inactive=ZONE.withActive(false);
        assertTrue(d.update(1000,List.of(inactive),List.of(track(4,.5,.5,true,0))).isEmpty());
        assertTrue(d.update(1100,List.of(ZONE),List.of(track(4,.5,.5,true,1))).isEmpty());
    }
    @Test void cooldownIsPerZoneAcrossDifferentTracks() {
        var d=new ZoneIntrusionDetector(1,60_000);
        assertTrue(d.update(1000,List.of(ZONE),List.of(track(4,.5,.5,true,0))).isEmpty());
        assertEquals(1,d.update(1100,List.of(ZONE),List.of(track(4,.5,.5,true,0))).size());
        assertTrue(d.update(1200,List.of(ZONE),List.of(track(5,.5,.5,true,0))).isEmpty());
        assertTrue(d.update(1300,List.of(ZONE),List.of(track(5,.5,.5,true,0))).isEmpty());
        assertTrue(d.update(61099,List.of(ZONE),List.of(track(6,.5,.5,true,0))).isEmpty());
        assertEquals(1,d.update(61100,List.of(ZONE),List.of(track(6,.5,.5,true,0))).size());
    }
    @Test void rejectsForeignCameraAndNonMonotonicFrames() {
        var d=new ZoneIntrusionDetector(1);
        assertThrows(IllegalArgumentException.class,()->d.update(1000,List.of(new ZoneDefinition(2,2,"Other",.1,.1,.2,.2,true)),List.of()));
        d.update(1000,List.of(),List.of());
        assertThrows(IllegalArgumentException.class,()->d.update(999,List.of(),List.of()));
    }
}
