package com.liskovsoft.smartyoutubetv2.common.proxy.builtin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * Fail-closed safety net while the built-in proxy mode is on.
 *
 * Installed as the JVM-wide default ProxySelector. Any Java HTTP stack that
 * consults the default selector (notably HttpURLConnection, which also covers
 * the ExoPlayer "default" data source and Glide's stock loader) is forced to
 * the loopback SOCKS inbound. If the v2ray core is down, the loopback port
 * refuses connections and requests FAIL — they never go direct.
 *
 * Non-HTTP(S) schemes return NO_PROXY: they are not web content paths, and the
 * Go core itself does not consult the Java selector at all.
 */
public class FailClosedProxySelector extends ProxySelector {
    private final List<Proxy> mHttpResult;
    private final List<Proxy> mDirectResult;

    public FailClosedProxySelector() {
        Proxy socks = new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(V2RayConfigBuilder.SOCKS_HOST, V2RayConfigBuilder.SOCKS_PORT));
        mHttpResult = Collections.singletonList(socks);
        mDirectResult = Collections.singletonList(Proxy.NO_PROXY);
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            return mHttpResult;
        }
        return mDirectResult;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // Fail-closed: no fallback action. The failure stands.
    }
}
