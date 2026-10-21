package com.liskovsoft.smartyoutubetv2.common.proxy.builtin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Downloads a subscription and extracts share links from it.
 *
 * BOOTSTRAP NOTE (by design): this fetch is DIRECT — it downloads
 * configuration, it is not app content traffic, and at first run it must work
 * before any proxy exists. It therefore uses HttpURLConnection with an
 * explicit Proxy.NO_PROXY, even while the built-in proxy mode is on. This is
 * the single documented exception to the fail-closed rule, and it applies
 * only to user-triggered subscription downloads from the config page.
 */
public class SubscriptionFetcher {
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    public static class Result {
        public List<ProxyNode> nodes;
        public String error;
    }

    public static Result fetch(String subscriptionUrl) {
        Result result = new Result();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(subscriptionUrl);
            // Explicit NO_PROXY: bootstrap config download (see class doc).
            connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "SmartTube-builtin-proxy/1.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                result.error = "Subscription server returned HTTP " + code;
                return result;
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        result.error = "Subscription response too large";
                        return result;
                    }
                    out.write(buffer, 0, n);
                }
                body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            String text = body;
            if (!text.contains("://")) {
                // Standard subscription payload: base64 of newline-joined links.
                try {
                    text = new String(ShareLinkParser.decodeBase64(text), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // Keep the raw body; the link scanner will find nothing.
                }
            }
            result.nodes = ShareLinkParser.parseMany(text);
            if (result.nodes.isEmpty()) {
                result.error = "No supported links found in the subscription";
            }
        } catch (Exception e) {
            result.error = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }
}
