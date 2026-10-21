package com.liskovsoft.smartyoutubetv2.common.proxy.builtin;

import android.content.Context;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.proxy.PasswdInetSocketAddress;
import com.liskovsoft.smartyoutubetv2.common.proxy.Proxy;
import com.liskovsoft.smartyoutubetv2.common.proxy.ProxyManager;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Orchestrates the three proxy modes:
 *   OFF      - no proxy (legacy defaults);
 *   EXTERNAL - the pre-existing web proxy (ProxyManager + WebProxyDialog), unchanged;
 *   BUILTIN  - the in-process v2ray engine; the existing plumbing is pointed at
 *              its loopback inbounds and a fail-closed selector is installed.
 *
 * FAIL-CLOSED contract for BUILTIN (owner spec): while the mode is on, every
 * Java HTTP path resolves to 127.0.0.1 inbounds only:
 *   - OkHttp clients via OkHttpCommons (system properties),
 *   - HttpURLConnection via http/https/socks system properties,
 *   - any default-ProxySelector consumer via FailClosedProxySelector,
 *   - WebView via ProxyManager's PROXY_CHANGE reflection.
 * If the engine is down the loopback ports refuse connections and requests
 * fail. There is no code path here that re-enables direct connections while
 * the mode stays BUILTIN.
 */
public class BuiltinProxyController {
    private static final String TAG = BuiltinProxyController.class.getSimpleName();

    public static final int MODE_OFF = 0;
    public static final int MODE_EXTERNAL = 1;
    public static final int MODE_BUILTIN = 2;

    private static final String LOOPBACK_URI = "socks://127.0.0.1:" + V2RayConfigBuilder.SOCKS_PORT;
    private static final String TEST_URL = "https://www.google.com/generate_204";

    private static BuiltinProxyController sInstance;

    private final Context mContext;
    private final AppPrefs mPrefs;
    private final ProxyConfigStore mStore;
    private final V2RayEngine mEngine;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    public interface TestCallback {
        void onResult(boolean success, String detail);
    }

    public static synchronized BuiltinProxyController instance(Context context) {
        if (sInstance == null) {
            sInstance = new BuiltinProxyController(context);
        }
        return sInstance;
    }

    private BuiltinProxyController(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = AppPrefs.instance(mContext);
        mStore = new ProxyConfigStore(mContext);
        mEngine = new V2RayEngine();
    }

    public static boolean isBuiltinActive(Context context) {
        return instance(context).getMode() == MODE_BUILTIN;
    }

    // ------------------------------------------------------------------ mode

    public int getMode() {
        int mode = mPrefs.getProxyMode();
        if (mode == -1) {
            // Migration: derive from the legacy flags, then persist.
            mode = mPrefs.isWebProxyEnabled() ? MODE_EXTERNAL : MODE_OFF;
            mPrefs.setProxyMode(mode);
        }
        return mode;
    }

    public void setMode(int mode) {
        int previous = getMode();
        mPrefs.setProxyMode(mode);
        GeneralData.instance(mContext).setProxyEnabled(mode != MODE_OFF);
        if (mode == MODE_BUILTIN) {
            applyBuiltin(true);
        } else {
            if (previous == MODE_BUILTIN) {
                teardownBuiltin();
            }
            applyLegacy();
        }
        OkHttpManager.unhold();
    }

    /** Called from SplashPresenter before content loads. */
    public void applyOnStartup() {
        int mode = getMode();
        if (mode == MODE_BUILTIN) {
            applyBuiltin(false);
        } else if (mode == MODE_EXTERNAL) {
            applyLegacy();
        }
    }

    // -------------------------------------------------------------- lifecycle

    private void applyBuiltin(boolean stashExternal) {
        if (stashExternal) {
            stashExternalPrefsIfNeeded();
        }
        pointPlumbingAtLoopback();
        startEngineWithCurrentConfig();
    }

    private void stashExternalPrefsIfNeeded() {
        if (mPrefs.hasBuiltinProxyStash()) {
            return;
        }
        String uri = mPrefs.getWebProxyUri();
        boolean enabled = mPrefs.isWebProxyEnabled();
        if (LOOPBACK_URI.equals(uri)) {
            // Already loopback (e.g. re-entry): nothing external to preserve.
            mPrefs.setBuiltinProxyStash("", false);
        } else {
            mPrefs.setBuiltinProxyStash(uri, enabled);
        }
    }

    private void pointPlumbingAtLoopback() {
        // 1) Shared prefs + WebView reflection via the existing ProxyManager.
        Proxy loopback = new Proxy(Proxy.Type.SOCKS, PasswdInetSocketAddress.createUnresolved(
                V2RayConfigBuilder.SOCKS_HOST, V2RayConfigBuilder.SOCKS_PORT, null, null));
        ProxyManager manager = new ProxyManager(mContext);
        manager.saveProxyInfoToPrefs(loopback, true);
        new ProxyManager(mContext).configureSystemProxy();

        // 2) configureSystemProxy() clears the http/https properties for a SOCKS
        //    proxy; restore them towards the loopback HTTP inbound so that
        //    HttpURLConnection consumers (ExoPlayer default data source, Glide)
        //    are proxied too. SOCKS properties stay as ProxyManager set them.
        System.setProperty("http.proxyHost", V2RayConfigBuilder.SOCKS_HOST);
        System.setProperty("http.proxyPort", String.valueOf(V2RayConfigBuilder.HTTP_PORT));
        System.setProperty("https.proxyHost", V2RayConfigBuilder.SOCKS_HOST);
        System.setProperty("https.proxyPort", String.valueOf(V2RayConfigBuilder.HTTP_PORT));
        System.setProperty("socksProxyHost", V2RayConfigBuilder.SOCKS_HOST);
        System.setProperty("socksProxyPort", String.valueOf(V2RayConfigBuilder.SOCKS_PORT));

        // 3) Fail-closed net for anything consulting the default selector.
        ProxySelector.setDefault(new FailClosedProxySelector());
    }

    private void teardownBuiltin() {
        mExecutor.execute(mEngine::stop);
        ProxySelector.setDefault(null);
        // Restore the user's external proxy settings verbatim.
        if (mPrefs.hasBuiltinProxyStash()) {
            mPrefs.setWebProxyUri(mPrefs.getBuiltinProxyStashUri());
            mPrefs.setWebProxyEnabled(mPrefs.isBuiltinProxyStashEnabled());
            mPrefs.clearBuiltinProxyStash();
        } else {
            mPrefs.setWebProxyUri("");
            mPrefs.setWebProxyEnabled(false);
        }
    }

    private void applyLegacy() {
        // Original behavior: ProxyManager applies (or clears) the system proxy
        // from the web-proxy prefs. In EXTERNAL mode with the legacy toggle off
        // this clears all proxy properties.
        new ProxyManager(mContext).configureSystemProxy();
    }

    // ---------------------------------------------------------------- engine

    /** (Re)start the engine with the currently stored configuration. */
    public void restartEngine() {
        if (getMode() == MODE_BUILTIN) {
            startEngineWithCurrentConfig();
        }
    }

    private void startEngineWithCurrentConfig() {
        mExecutor.execute(() -> {
            String configJson = buildEffectiveConfigJson();
            if (configJson == null) {
                mEngine.stop();
                Log.w(TAG, "Built-in proxy enabled but no configuration stored yet");
                return;
            }
            mEngine.start(mContext, configJson);
        });
    }

    /** Full v2ray config for the current store state, or null when unconfigured. */
    public String buildEffectiveConfigJson() {
        try {
            String raw = mStore.loadRawConfig();
            if (raw != null && !raw.isEmpty()) {
                return V2RayConfigBuilder.adaptRawConfig(raw);
            }
            ProxyNode active = getActiveNode();
            if (active != null) {
                return V2RayConfigBuilder.buildConfig(active);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build v2ray config: " + e.getClass().getSimpleName());
        }
        return null;
    }

    public V2RayEngine.State getEngineState() {
        return mEngine.getState();
    }

    public String getEngineError() {
        return mEngine.getLastError();
    }

    public String getCoreVersion() {
        return mEngine.getCoreVersion();
    }

    public ProxyConfigStore getStore() {
        return mStore;
    }

    // ------------------------------------------------------------------ nodes

    public List<ProxyNode> getNodes() {
        return mStore.loadNodes();
    }

    public ProxyNode getActiveNode() {
        List<ProxyNode> nodes = mStore.loadNodes();
        if (nodes.isEmpty()) {
            return null;
        }
        String activeId = mPrefs.getBuiltinProxyActiveNodeId();
        for (ProxyNode node : nodes) {
            if (node.id != null && node.id.equals(activeId)) {
                return node;
            }
        }
        return nodes.get(0);
    }

    public void setActiveNodeId(String nodeId) {
        mPrefs.setBuiltinProxyActiveNodeId(nodeId == null ? "" : nodeId);
    }

    public boolean isRawConfigMode() {
        String raw = mStore.loadRawConfig();
        return raw != null && !raw.isEmpty();
    }

    // ------------------------------------------------------------------- test

    /**
     * Connection test per spec: HTTPS GET generate_204 explicitly through the
     * local SOCKS inbound (validates engine + the exact socket the app uses).
     */
    public void testConnection(TestCallback callback) {
        mExecutor.execute(() -> {
            if (!mEngine.isCoreRunning()) {
                callback.onResult(false, "Engine is not running (" + mEngine.getState() + ")");
                return;
            }
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                            new InetSocketAddress(V2RayConfigBuilder.SOCKS_HOST, V2RayConfigBuilder.SOCKS_PORT)))
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            long start = System.currentTimeMillis();
            try (Response response = client.newCall(new Request.Builder().url(TEST_URL).build()).execute()) {
                long elapsed = System.currentTimeMillis() - start;
                boolean ok = response.code() == 204 || response.code() == 200;
                callback.onResult(ok, "HTTP " + response.code() + " in " + elapsed + " ms via built-in proxy");
            } catch (Exception e) {
                callback.onResult(false, e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "" : e.getMessage()));
            }
        });
    }
}
