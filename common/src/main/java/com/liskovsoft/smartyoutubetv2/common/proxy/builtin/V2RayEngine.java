package com.liskovsoft.smartyoutubetv2.common.proxy.builtin;

import android.content.Context;
import android.content.res.AssetManager;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * Lifecycle wrapper around the in-process v2ray core (AndroidLibV2rayLite
 * v5.55.0, minSdk 21). The core opens SOCKS/HTTP inbounds on 127.0.0.1 only
 * (see V2RayConfigBuilder) and exists solely for this app's own traffic:
 * no VpnService, no TUN, nothing system-wide.
 *
 * All native calls are guarded with Throwable: on a device whose ABI is not
 * shipped in the AAR the engine reports ERROR instead of crashing the app
 * (and, fail-closed, traffic simply has nowhere to go).
 */
public class V2RayEngine {
    private static final String TAG = V2RayEngine.class.getSimpleName();
    private static final String LIB_VERSION = "5.55.0";
    private static final String[] GEO_ASSETS = {"geoip.dat", "geosite.dat"};

    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    private CoreController mController;
    private State mState = State.STOPPED;
    private String mLastError;
    private boolean mEnvInitialized;

    public synchronized State getState() {
        if (mState == State.RUNNING && !isCoreRunning()) {
            mState = State.ERROR;
            mLastError = "Core stopped unexpectedly";
        }
        return mState;
    }

    public synchronized String getLastError() {
        return mLastError;
    }

    public String getCoreVersion() {
        try {
            return Libv2ray.checkVersionX();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Blocking. Call from a worker thread. Returns true when the core is up. */
    public synchronized boolean start(Context context, String configJson) {
        stopInternal();
        mState = State.STARTING;
        mLastError = null;
        try {
            ensureEnv(context);
            if (mController == null) {
                mController = Libv2ray.newCoreController(new CoreCallbackHandler() {
                    @Override
                    public long onEmitStatus(long code, String message) {
                        // Message text is intentionally not logged: core status
                        // strings can quote parts of the config (secrets).
                        Log.d(TAG, "Core status code: " + code);
                        return 0;
                    }

                    @Override
                    public long startup() {
                        return 0;
                    }

                    @Override
                    public long shutdown() {
                        return 0;
                    }
                });
            }
            mController.startLoop(configJson);
            mState = isCoreRunning() ? State.RUNNING : State.ERROR;
            if (mState != State.RUNNING) {
                mLastError = "Core did not enter running state";
            }
        } catch (Throwable e) {
            mState = State.ERROR;
            mLastError = e.getClass().getSimpleName() + ": " + safeMessage(e);
            Log.e(TAG, "Failed to start v2ray core: " + mLastError);
        }
        return mState == State.RUNNING;
    }

    /** Blocking. Safe to call from any thread. */
    public synchronized void stop() {
        stopInternal();
        mState = State.STOPPED;
    }

    private void stopInternal() {
        if (mController != null) {
            try {
                if (mController.getIsRunning()) {
                    mController.stopLoop();
                }
            } catch (Throwable e) {
                Log.e(TAG, "Error while stopping core: " + e.getClass().getSimpleName());
            }
        }
    }

    public boolean isCoreRunning() {
        try {
            return mController != null && mController.getIsRunning();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Delay in ms through the running core, or -1. Blocking; worker thread. */
    public long measureDelay(String url) {
        try {
            if (mController != null && mController.getIsRunning()) {
                return mController.measureDelay(url);
            }
        } catch (Throwable e) {
            Log.e(TAG, "measureDelay failed: " + e.getClass().getSimpleName());
        }
        return -1;
    }

    private void ensureEnv(Context context) throws Exception {
        if (mEnvInitialized) {
            return;
        }
        File filesDir = context.getApplicationContext().getFilesDir();
        File marker = new File(filesDir, "libv2ray-assets-" + LIB_VERSION);
        if (!marker.exists()) {
            AssetManager assets = context.getApplicationContext().getAssets();
            for (String name : GEO_ASSETS) {
                copyAsset(assets, name, new File(filesDir, name));
            }
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
        }
        Libv2ray.initCoreEnv(filesDir.getAbsolutePath(), "");
        mEnvInitialized = true;
    }

    private void copyAsset(AssetManager assets, String name, File dest) throws Exception {
        try (InputStream in = assets.open(name);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
        }
    }

    private String safeMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return "";
        }
        // Error strings from the core may quote config fragments; keep them short.
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
