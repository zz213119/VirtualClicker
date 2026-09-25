// IVirtualDisplayService.aidl
package com.zz213119.virtualclicker.service;

import android.view.Surface;

// Runs inside the Shizuku UserService process (shell UID).
// Phase 1 scope: create a headless virtual display and launch
// a target app's launcher activity into it.
interface IVirtualDisplayService {

    // Creates a virtual display and returns its displayId, or -1 on failure.
    // "Blind" variant: renders into an internal, unreachable sink — nothing
    // to look at, only useful for input-injection testing.
    int createVirtualDisplay(String name, int width, int height, int dpi);

    // Same as above, but renders into a Surface supplied by the caller (e.g.
    // a SurfaceView's Surface in MainActivity) so the app can show a live
    // preview of whatever is running on the virtual display.
    int createVirtualDisplayWithSurface(String name, int width, int height, int dpi, in Surface surface);

    // Changes the rendering surface without destroying the virtual display.
    // Passing null detaches the current Surface; a later call can reattach one.
    boolean setVirtualDisplaySurface(int displayId, in Surface surface);

    // Resolves packageName's own launcher activity and starts it on displayId.
    boolean launchApp(String packageName, int displayId);

    // Starts an explicit component on displayId (use when resolveLaunchActivity fails
    // or the caller already knows the activity, e.g. from a saved task).
    boolean launchAppExplicit(String packageName, String activityName, int displayId);

    // Releases a display created by this service.
    void releaseVirtualDisplay(int displayId);

    // Sends a single touchscreen tap to the target virtual display.
    boolean tap(int displayId, float x, float y);

    // Sends a long press by holding a touch point for durationMs.
    boolean longPress(int displayId, float x, float y, int durationMs);

    // Sends a touchscreen swipe to the target virtual display.
    boolean swipe(
        int displayId,
        float x1,
        float y1,
        float x2,
        float y2,
        int durationMs
    );

    // Called by Shizuku when the UserService is torn down.
    void destroy();
}
