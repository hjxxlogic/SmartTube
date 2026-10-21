package com.liskovsoft.smartyoutubetv2.common.proxy.builtin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds a complete v2ray config (v5 format, v4-compatible subset) around one
 * active node, or adapts a user-supplied raw config by replacing its inbounds
 * with the app-private loopback ones.
 *
 * Fixed policy (owner spec, fail-closed):
 * - inbounds: SOCKS5 + HTTP on 127.0.0.1 only;
 * - routing: a single catch-all rule sends all tcp/udp to the proxy outbound;
 * - DNS: plain-IP upstreams whose queries are routed through the tunnel like
 *   any other traffic (no "+local" resolvers, so no LAN DNS leak).
 */
public class V2RayConfigBuilder {
    public static final String SOCKS_HOST = "127.0.0.1";
    public static final int SOCKS_PORT = 10808;
    public static final int HTTP_PORT = 10809;

    private static final String TAG_PROXY = "proxy";

    public static String buildConfig(ProxyNode node) throws JSONException {
        JSONObject config = new JSONObject();
        config.put("log", new JSONObject().put("loglevel", "warning"));
        config.put("dns", buildDns());
        config.put("inbounds", buildInbounds());
        JSONArray outbounds = new JSONArray();
        outbounds.put(buildOutbound(node, TAG_PROXY));
        outbounds.put(new JSONObject()
                .put("tag", "direct")
                .put("protocol", "freedom"));
        outbounds.put(new JSONObject()
                .put("tag", "block")
                .put("protocol", "blackhole")
                .put("settings", new JSONObject().put("response", new JSONObject().put("type", "http"))));
        config.put("outbounds", outbounds);
        config.put("routing", buildCatchAllRouting());
        config.put("stats", new JSONObject());
        config.put("policy", new JSONObject()
                .put("system", new JSONObject()
                        .put("statsOutboundUplink", true)
                        .put("statsOutboundDownlink", true)));
        return config.toString();
    }

    /**
     * Take a user-supplied raw v2ray JSON config and force our loopback
     * inbounds into it. Outbounds/routing of the raw config are kept; if it has
     * no routing section, a catch-all rule to the first outbound is added.
     */
    public static String adaptRawConfig(String rawJson) throws JSONException {
        JSONObject config = new JSONObject(rawJson);
        config.put("inbounds", buildInbounds());
        if (!config.has("dns")) {
            config.put("dns", buildDns());
        }
        if (!config.has("routing")) {
            JSONArray outbounds = config.optJSONArray("outbounds");
            String firstTag = "proxy";
            if (outbounds != null && outbounds.length() > 0) {
                firstTag = outbounds.getJSONObject(0).optString("tag", "proxy");
            }
            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "AsIs");
            JSONArray rules = new JSONArray();
            rules.put(new JSONObject()
                    .put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", firstTag));
            routing.put("rules", rules);
            config.put("routing", routing);
        }
        return config.toString();
    }

    private static JSONObject buildDns() throws JSONException {
        JSONArray servers = new JSONArray();
        servers.put("1.1.1.1");
        servers.put("8.8.8.8");
        return new JSONObject()
                .put("servers", servers)
                .put("queryStrategy", "UseIP");
    }

    private static JSONArray buildInbounds() throws JSONException {
        JSONObject sniffing = new JSONObject()
                .put("enabled", true)
                .put("destOverride", new JSONArray().put("http").put("tls"));

        JSONObject socks = new JSONObject()
                .put("tag", "socks-in")
                .put("listen", SOCKS_HOST)
                .put("port", SOCKS_PORT)
                .put("protocol", "socks")
                .put("settings", new JSONObject()
                        .put("auth", "noauth")
                        .put("udp", true))
                .put("sniffing", sniffing);

        JSONObject http = new JSONObject()
                .put("tag", "http-in")
                .put("listen", SOCKS_HOST)
                .put("port", HTTP_PORT)
                .put("protocol", "http")
                .put("settings", new JSONObject().put("allowTransparent", false))
                .put("sniffing", sniffing);

        return new JSONArray().put(socks).put(http);
    }

    private static JSONObject buildCatchAllRouting() throws JSONException {
        JSONArray rules = new JSONArray();
        rules.put(new JSONObject()
                .put("type", "field")
                .put("network", "tcp,udp")
                .put("outboundTag", TAG_PROXY));
        return new JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", rules);
    }

    // -------------------------------------------------------------- outbounds

    static JSONObject buildOutbound(ProxyNode node, String tag) throws JSONException {
        JSONObject outbound = new JSONObject();
        outbound.put("tag", tag);
        outbound.put("protocol", node.protocol);

        JSONObject settings = new JSONObject();
        switch (node.protocol) {
            case ProxyNode.PROTOCOL_VMESS: {
                JSONObject user = new JSONObject()
                        .put("id", node.userId)
                        .put("alterId", node.alterId)
                        .put("security", isEmpty(node.security) ? "auto" : node.security);
                JSONObject vnext = new JSONObject()
                        .put("address", node.address)
                        .put("port", node.port)
                        .put("users", new JSONArray().put(user));
                settings.put("vnext", new JSONArray().put(vnext));
                break;
            }
            case ProxyNode.PROTOCOL_VLESS: {
                JSONObject user = new JSONObject()
                        .put("id", node.userId)
                        .put("encryption", "none");
                if (!isEmpty(node.flow)) {
                    user.put("flow", node.flow);
                }
                JSONObject vnext = new JSONObject()
                        .put("address", node.address)
                        .put("port", node.port)
                        .put("users", new JSONArray().put(user));
                settings.put("vnext", new JSONArray().put(vnext));
                break;
            }
            case ProxyNode.PROTOCOL_TROJAN: {
                JSONObject server = new JSONObject()
                        .put("address", node.address)
                        .put("port", node.port)
                        .put("password", node.userId);
                settings.put("servers", new JSONArray().put(server));
                break;
            }
            case ProxyNode.PROTOCOL_SS: {
                JSONObject server = new JSONObject()
                        .put("address", node.address)
                        .put("port", node.port)
                        .put("method", node.security)
                        .put("password", node.userId)
                        .put("udp", true);
                settings.put("servers", new JSONArray().put(server));
                break;
            }
            default:
                throw new JSONException("Unsupported protocol: " + node.protocol);
        }
        outbound.put("settings", settings);

        JSONObject stream = buildStreamSettings(node);
        if (stream != null) {
            outbound.put("streamSettings", stream);
        }
        return outbound;
    }

    private static JSONObject buildStreamSettings(ProxyNode node) throws JSONException {
        String network = isEmpty(node.network) ? "tcp" : node.network;
        boolean plainTcp = "tcp".equals(network) && isEmpty(node.tls) && isEmpty(node.headerType);
        if (plainTcp) {
            return null;
        }
        JSONObject stream = new JSONObject();
        stream.put("network", network);

        switch (network) {
            case "ws": {
                JSONObject ws = new JSONObject();
                if (!isEmpty(node.path)) {
                    ws.put("path", node.path);
                }
                if (!isEmpty(node.host)) {
                    ws.put("headers", new JSONObject().put("Host", node.host));
                }
                stream.put("wsSettings", ws);
                break;
            }
            case "grpc": {
                stream.put("grpcSettings", new JSONObject()
                        .put("serviceName", node.path == null ? "" : node.path)
                        .put("multiMode", false));
                break;
            }
            case "http": { // h2
                JSONObject http = new JSONObject();
                if (!isEmpty(node.path)) {
                    http.put("path", node.path);
                }
                if (!isEmpty(node.host)) {
                    JSONArray hosts = new JSONArray();
                    for (String h : node.host.split(",")) {
                        hosts.put(h.trim());
                    }
                    http.put("host", hosts);
                }
                stream.put("httpSettings", http);
                break;
            }
            case "kcp": {
                JSONObject header = new JSONObject().put("type", isEmpty(node.headerType) ? "none" : node.headerType);
                stream.put("kcpSettings", new JSONObject().put("header", header));
                break;
            }
            case "tcp": {
                if (!isEmpty(node.headerType) && !"none".equals(node.headerType)) {
                    // TCP + HTTP camouflage header
                    JSONObject request = new JSONObject()
                            .put("version", "1.1")
                            .put("method", "GET")
                            .put("path", new JSONArray().put(isEmpty(node.path) ? "/" : node.path))
                            .put("headers", new JSONObject()
                                    .put("Host", new JSONArray().put(isEmpty(node.host) ? node.address : node.host)));
                    stream.put("tcpSettings", new JSONObject()
                            .put("header", new JSONObject()
                                    .put("type", node.headerType)
                                    .put("request", request)));
                }
                break;
            }
            default:
                break;
        }

        if ("tls".equals(node.tls)) {
            stream.put("security", "tls");
            JSONObject tls = new JSONObject();
            tls.put("serverName", firstNonEmpty(node.sni, node.host, node.address));
            tls.put("allowInsecure", node.allowInsecure);
            if (!isEmpty(node.alpn)) {
                JSONArray alpn = new JSONArray();
                for (String a : node.alpn.split(",")) {
                    alpn.put(a.trim());
                }
                tls.put("alpn", alpn);
            }
            if (!isEmpty(node.fingerprint)) {
                tls.put("fingerprint", node.fingerprint);
            }
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(node.tls)) {
            stream.put("security", "reality");
            JSONObject reality = new JSONObject();
            reality.put("serverName", firstNonEmpty(node.sni, node.address));
            reality.put("fingerprint", isEmpty(node.fingerprint) ? "chrome" : node.fingerprint);
            reality.put("publicKey", node.publicKey == null ? "" : node.publicKey);
            reality.put("shortId", node.shortId == null ? "" : node.shortId);
            reality.put("spiderX", "");
            stream.put("realitySettings", reality);
        }
        return stream;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (!isEmpty(v)) {
                // host may be a comma list (h2); take the first entry for SNI use
                int comma = v.indexOf(',');
                return comma >= 0 ? v.substring(0, comma).trim() : v;
            }
        }
        return "";
    }
}
