# Camera management (Phase 8: first-class camera configuration)

Status: implemented and evidenced on the rig (2026-11-17). Owned by the app side
(`com.cctv.app.db.CameraStore`, `ui.CameraManagerView`, `ui.CameraForm`, `net.RtspProbe`).

## Requirement

The shipped app must let a user manage cameras without editing a file:

- a visible **Cameras** entry point and **+ Add camera**;
- a list showing name, enabled state, live status, source, record and AI flags;
- an add/edit form with RTSP main and substream URLs, optional username/password,
  a connection test, save and cancel;
- an explicit remove confirmation; removal stops the streams but keeps history
  (clips, labels, alerts, counts);
- enable/disable without deleting the camera;
- one to four cameras, with an empty-slot affordance when fewer are configured;
- persistence across restart, duplicate and invalid input rejected with clear messages,
  and honest offline/auth/codec errors.

## Storage: SQLite is the runtime source of truth

`cameras` now carries two extra columns (schema v3):

| column | meaning |
|---|---|
| `enabled` | 1 while the camera should play; 0 keeps the row but stops the streams |
| `deleted` | 1 after a user removal (soft delete); history rows keep pointing at the camera id |

`Database.migrate` adds both columns to a v2 database with `ALTER TABLE`, so an
existing installation upgrades in place.

`cameras.properties` becomes a **first-run import only**: on startup
`CameraStore.importIfEmpty(config.cameras())` inserts the file's cameras when the
table is empty and logs `cameras imported from …`. After that the database wins, so
a user edit is never overwritten by the file.

## CameraStore

`CameraStore(Path dbFile)`:

| method | notes |
|---|---|
| `all()` / `enabled()` / `byId(long)` | `enabled()` returns only `deleted = 0 AND enabled = 1` |
| `add(Draft)` | returns the new id; rejects a fifth camera (`MAX_CAMERAS = 4`) |
| `update(long, Draft)` / `setEnabled(long, boolean)` | edit in place; a renamed camera keeps its history |
| `remove(long)` | soft delete (`deleted = 1, enabled = 0`); recordings/alerts/counts stay |
| `importIfEmpty(List<CameraConfig>)` | used once, from the properties file |
| `activeCount()` | non-deleted cameras, for the four-camera cap |

Validation (throws `IllegalArgumentException` with a user-facing message): blank or
over-long name, non-`rtsp`/`rtsps` scheme, missing host, neither URL set, record or AI
without a main URL, duplicate name (case-insensitive) or duplicate URL across active
cameras. `displayUrl()` always runs the URL through `Redaction.redact`, so a list row
never shows a password.

## Connection test

`RtspProbe.probe(URI, Duration)` runs a `FFmpegFrameGrabber` on a short-lived daemon
executor (`rtsp_transport=tcp`, 5 s socket and analysis timeouts), grabs one frame and
reports `connected - WxH codec`. Failures are redacted and truncated before they reach
the UI. The button is disabled while the camera is enabled, because the app must not
open a second connection to a stream profile that is already playing.

## UI flow

- header **Cameras** → `CameraManagerView` (overlay above the wall, `Esc` closes it);
- `+ Add camera` / double-click or **Edit** → `CameraForm` modal;
- **Disable** / **Enable** toggles `enabled`; **Remove** asks for confirmation and
  explains that history is kept;
- every store call happens on a daemon worker, the table refreshes via `Platform.runLater`;
- an empty wall slot is a dashed **+ Add camera** button instead of a dead rectangle.

Saving a change calls `CamWatchmanApp.rebuildWall()`:

1. the engine handle is closed **synchronously** (the new wall must never open a second
   connection to Camera 1), VLCJ viewers and the detection overlay are closed;
2. `refreshActiveCameras()` reloads `enabled` cameras from SQLite;
3. the grid is rebuilt and re-attached to the overlay stack, viewers are started,
   `startEngine()`, `refreshOverlays()` and `publishInitialStates()` run again.

## Evidence

| screenshot | what it shows |
|---|---|
| `evidence/8-manage-cameras.png` | manager listing four rig cameras: status, record/AI flags, enabled, redacted source, `4 camera(s) configured · 4 enabled` |
| `evidence/8-manage-added.png` | after `remove(camera 4)` + `add(Demo extra #5)`: wall rebuilt, camera 4 gone, Demo extra live on `cam4_sub`, camera 1 restarted and `ONLINE · REC` |
| `evidence/8-manage-persisted.png` | next launch, no switches: camera 5 still on the wall from SQLite, all four streaming |

Reproduce (throwaway data dir, rig running):

```powershell
# manager view
java -Dcctv.config=tmp\manager.properties -Dcctv.open.manager=1 `
     -Dcctv.snapshot=evidence\8-manage-cameras.png -Dcctv.snapshot.delay=6 `
     -cp "target\classes;<cp.txt>" com.cctv.app.Main

# add a camera + rebuild the wall (removes the last rig camera first, four-camera cap)
java -Dcctv.config=tmp\manager.properties -Dcctv.cameras.demo=add `
     -Dcctv.snapshot=evidence\8-manage-added.png -Dcctv.snapshot.delay=18 ...
```

## Tests

`src/test/java/com/cctv/app/db/CameraStoreTest.java` (10 tests): add/list, import only
when empty, invalid drafts, duplicate name/URL, update, disable/enable, soft remove
preserving a recordings row, reopen persistence, credential redaction, and the
four-camera cap.

Full suite: `mvnw.cmd test` → 125 tests, 0 failures.

## Limits

- exactly one camera can run the full pipeline (the first enabled camera with record or
  AI); the rest are display-only, matching the build plan's one-pipeline rule;
- the manager is an in-app overlay, not a separate window, so it is captured by the
  snapshot evidence harness;
- status text in the table is truncated by column width (hover shows the full detail in
  the tooltip added by JavaFX's default table behaviour);
- removing a camera drops its zones/lines UI entries but not its historical rows;
- `cameras.properties` is still read for the first-run import and for `base.data.dir`,
  segment length, AI rate and retention settings; only the camera list moved to SQLite.

## Packaged and form evidence (same batch)

| screenshot | what it shows |
|---|---|
| `evidence/8-manage-form.png` | edit form for `Rig cam 2` (name, main/substream, optional user/password, record/AI/enabled, Save, connection test disabled while enabled, Cancel) — the form is a separate modal stage, so its own scene is captured via `-Dcctv.form.snapshot=<path>` |
| `evidence/8-manage-packaged.png` | the same manager screen inside the packaged app-image, launched as `dist\CamWatchman\CamWatchman.exe --manage-cameras=1` |

Launcher arguments added in `Main.applyArguments`: `--manage-cameras` maps to `cctv.open.manager`.

The form is a separate `Stage` (APPLICATION_MODAL), so the in-window snapshot harness cannot
see it; `-Dcctv.form.snapshot=<file>` writes the form's own scene to a PNG for evidence.

## Runtime acceptance matrix (navigation)

`-Dcctv.nav.demo=1` drives the whole matrix headlessly in one run and logs every hop, so the
transition sequence is provable from the log (the trailing snapshot shows the final wall):

```
nav: search open   -> nav: back to wall
nav: cameras open  -> nav: back to wall
nav: counts open   -> nav: back to wall
```

Evidence: `evidence/8-nav-return.png` (wall after the round trip, all cameras live, Camera 1
`ONLINE · REC`). Every overlay closes back to the wall without restarting a stream.
