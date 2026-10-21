package com.liskovsoft.smartyoutubetv2.common.settingsserver;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Enumeration;

/**
 * Owns the phone-config session: token, server lifetime, LAN address.
 *
 * The server starts only when the QR screen opens and stops when the screen
 * closes, after a successful save, or after 5 minutes — whichever comes
 * first. It is never a background service.
 */
public class PhoneConfigManager {
    public static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int PORT_FIRST = 18080;
    private static final int PORT_LAST = 18099;

    public interface Listener {
        void onConfigSaved(String message);
        void onServerStopped();
    }

    private static PhoneConfigManager sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SettingsHttpServer mServer;
    private String mToken;
    private String mUrl;
    private boolean mConsumed;
    private Listener mListener;

    private final Runnable mAutoStop = this::stop;

    public static synchronized PhoneConfigManager instance() {
        if (sInstance == null) {
            sInstance = new PhoneConfigManager();
        }
        return sInstance;
    }

    /**
     * Start a fresh session (stopping any previous one).
     * Returns the URL to encode in the QR code.
     * @throws IllegalStateException with a user-readable reason on failure.
     */
    public synchronized String start(Context context, Listener listener) {
        stopInternal();
        mListener = listener;
        mConsumed = false;

        String ip = findLanIpAddress();
        if (ip == null) {
            throw new IllegalStateException("No LAN connection found. Connect the TV to your home network first.");
        }
        mToken = generateToken();
        SettingsRegistry registry = new SettingsRegistry(context.getApplicationContext());

        IOException lastError = null;
        for (int port = PORT_FIRST; port <= PORT_LAST; port++) {
            try {
                mServer = new SettingsHttpServer(port, registry, this);
                mServer.start(30_000, false);
                mUrl = "http://" + ip + ":" + port + "/?t=" + mToken;
                mHandler.postDelayed(mAutoStop, SESSION_TIMEOUT_MS);
                return mUrl;
            } catch (IOException e) {
                lastError = e;
                mServer = null;
            }
        }
        throw new IllegalStateException("Could not start the configuration server"
                + (lastError == null ? "" : ": " + lastError.getMessage()));
    }

    public synchronized void stop() {
        boolean wasRunning = mServer != null;
        stopInternal();
        if (wasRunning && mListener != null) {
            mListener.onServerStopped();
        }
    }

    private void stopInternal() {
        mHandler.removeCallbacks(mAutoStop);
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
        mToken = null;
        mUrl = null;
    }

    public synchronized boolean isRunning() {
        return mServer != null && mServer.isAlive();
    }

    public synchronized String getUrl() {
        return mUrl;
    }

    synchronized String getToken() {
        return mToken;
    }

    synchronized boolean isConsumed() {
        return mConsumed;
    }

    /** Called by the web server after a successful save. */
    synchronized void onSaveSuccess(String message) {
        mConsumed = true;
        if (mListener != null) {
            mListener.onConfigSaved(message);
        }
        // Let the HTTP response flush, then shut the session down.
        mHandler.postDelayed(this::stop, 1500);
    }

    private String generateToken() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * First site-local IPv4 address, preferring wired (eth*) interfaces —
     * TVs are usually on Ethernet, where WifiManager reports nothing.
     */
    static String findLanIpAddress() {
        String wifiFallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        if (iface.getName().startsWith("eth")) {
                            return address.getHostAddress();
                        }
                        if (wifiFallback == null) {
                            wifiFallback = address.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through: caller reports "no LAN".
        }
        return wifiFallback;
    }
}
