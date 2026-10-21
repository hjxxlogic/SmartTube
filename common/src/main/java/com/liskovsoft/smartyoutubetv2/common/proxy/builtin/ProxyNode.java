package com.liskovsoft.smartyoutubetv2.common.proxy.builtin;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One outbound proxy node (a single server entry), independent of protocol.
 * Secrets (uuid / password) live in this object and in the private config store
 * only: they are never logged and never rendered into the phone config page.
 */
public class ProxyNode {
    public static final String PROTOCOL_VMESS = "vmess";
    public static final String PROTOCOL_VLESS = "vless";
    public static final String PROTOCOL_TROJAN = "trojan";
    public static final String PROTOCOL_SS = "shadowsocks";

    public String id;            // stable local id (assigned by the store/parser)
    public String name;          // user-visible label (from the link's remark), no secrets
    public String protocol;
    public String address;
    public int port;
    public String userId;        // vmess/vless uuid, trojan password, ss password
    public int alterId;          // vmess only
    public String security;      // vmess cipher / ss method / vless flow is separate
    public String flow;          // vless only (e.g. xtls-rprx-vision)
    public String network = "tcp"; // tcp, ws, grpc, http (h2), kcp
    public String headerType = ""; // kcp/tcp camouflage header type
    public String tls = "";      // "", "tls", "reality"
    public String sni = "";
    public String host = "";     // ws/http Host header (comma separated for h2)
    public String path = "";     // ws path / grpc service name / h2 path
    public String alpn = "";
    public String fingerprint = ""; // tls fingerprint (uTLS), "" = default
    public String publicKey = "";   // reality only
    public String shortId = "";     // reality only
    public boolean allowInsecure;

    public String getDisplayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return protocol + " " + address + ":" + port;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name == null ? "" : name);
        obj.put("protocol", protocol);
        obj.put("address", address);
        obj.put("port", port);
        obj.put("userId", userId == null ? "" : userId);
        obj.put("alterId", alterId);
        obj.put("security", security == null ? "" : security);
        obj.put("flow", flow == null ? "" : flow);
        obj.put("network", network == null ? "tcp" : network);
        obj.put("headerType", headerType == null ? "" : headerType);
        obj.put("tls", tls == null ? "" : tls);
        obj.put("sni", sni == null ? "" : sni);
        obj.put("host", host == null ? "" : host);
        obj.put("path", path == null ? "" : path);
        obj.put("alpn", alpn == null ? "" : alpn);
        obj.put("fingerprint", fingerprint == null ? "" : fingerprint);
        obj.put("publicKey", publicKey == null ? "" : publicKey);
        obj.put("shortId", shortId == null ? "" : shortId);
        obj.put("allowInsecure", allowInsecure);
        return obj;
    }

    public static ProxyNode fromJson(JSONObject obj) throws JSONException {
        ProxyNode node = new ProxyNode();
        node.id = obj.optString("id");
        node.name = obj.optString("name");
        node.protocol = obj.optString("protocol");
        node.address = obj.optString("address");
        node.port = obj.optInt("port");
        node.userId = obj.optString("userId");
        node.alterId = obj.optInt("alterId");
        node.security = obj.optString("security");
        node.flow = obj.optString("flow");
        node.network = obj.optString("network", "tcp");
        node.headerType = obj.optString("headerType");
        node.tls = obj.optString("tls");
        node.sni = obj.optString("sni");
        node.host = obj.optString("host");
        node.path = obj.optString("path");
        node.alpn = obj.optString("alpn");
        node.fingerprint = obj.optString("fingerprint");
        node.publicKey = obj.optString("publicKey");
        node.shortId = obj.optString("shortId");
        node.allowInsecure = obj.optBoolean("allowInsecure");
        return node;
    }
}
