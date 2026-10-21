package com.liskovsoft.smartyoutubetv2.common.proxy.builtin;

import android.util.Base64;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses v2ray share links into {@link ProxyNode}s.
 * Supported: vmess:// (base64 JSON), vless:// (URI), trojan:// (URI),
 * ss:// (SIP002 and legacy base64 forms).
 * NOTE: parse errors never include the link text in messages (links carry secrets).
 */
public class ShareLinkParser {

    public static boolean isSupportedLink(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.startsWith("vmess://") || t.startsWith("vless://")
                || t.startsWith("trojan://") || t.startsWith("ss://");
    }

    /** Parse every supported link found in a (multi-line) text; bad lines are skipped. */
    public static List<ProxyNode> parseMany(String text) {
        List<ProxyNode> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (isSupportedLink(t)) {
                try {
                    result.add(parse(t));
                } catch (Exception ignored) {
                    // skip malformed link; never log the link itself (contains secrets)
                }
            }
        }
        return result;
    }

    public static ProxyNode parse(String link) throws Exception {
        String t = link.trim();
        ProxyNode node;
        if (t.startsWith("vmess://")) {
            node = parseVmess(t.substring("vmess://".length()));
        } else if (t.startsWith("vless://")) {
            node = parseUriStyle(t, ProxyNode.PROTOCOL_VLESS);
        } else if (t.startsWith("trojan://")) {
            node = parseUriStyle(t, ProxyNode.PROTOCOL_TROJAN);
        } else if (t.startsWith("ss://")) {
            node = parseShadowsocks(t.substring("ss://".length()));
        } else {
            throw new IllegalArgumentException("Unsupported link scheme");
        }
        node.id = stableId(node);
        return node;
    }

    // ------------------------------------------------------------------ vmess

    private static ProxyNode parseVmess(String body) throws Exception {
        String json = new String(decodeBase64(body), StandardCharsets.UTF_8);
        JSONObject obj = new JSONObject(json);
        ProxyNode node = new ProxyNode();
        node.protocol = ProxyNode.PROTOCOL_VMESS;
        node.name = obj.optString("ps");
        node.address = obj.optString("add");
        node.port = parsePort(obj.optString("port"), 443);
        node.userId = obj.optString("id");
        node.alterId = obj.optInt("aid", 0);
        node.security = obj.optString("scy", "auto");
        node.network = emptyTo(obj.optString("net"), "tcp");
        node.headerType = obj.optString("type", "");
        node.host = obj.optString("host");
        node.path = obj.optString("path");
        String tls = obj.optString("tls");
        node.tls = "tls".equals(tls) ? "tls" : "";
        node.sni = obj.optString("sni");
        node.alpn = obj.optString("alpn");
        node.fingerprint = obj.optString("fp");
        node.allowInsecure = obj.optInt("allowInsecure", 0) == 1;
        if (node.address.isEmpty() || node.userId == null || node.userId.isEmpty()) {
            throw new IllegalArgumentException("vmess link missing address or uuid");
        }
        return node;
    }

    // ------------------------------------------------------- vless / trojan

    private static ProxyNode parseUriStyle(String link, String protocol) throws Exception {
        // link: scheme://user@host:port?query#name
        String rest = link.substring(link.indexOf("://") + 3);
        String name = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            name = urlDecode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int at = rest.lastIndexOf('@');
        if (at < 0) {
            throw new IllegalArgumentException("Link missing user info");
        }
        String user = urlDecode(rest.substring(0, at));
        String hostPort = rest.substring(at + 1);
        HostPort hp = parseHostPort(hostPort, 443);

        ProxyNode node = new ProxyNode();
        node.protocol = protocol;
        node.name = name;
        node.address = hp.host;
        node.port = hp.port;
        node.userId = user;

        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? urlDecode(pair.substring(eq + 1)) : "";
            switch (key) {
                case "security":
                    node.tls = "reality".equals(value) ? "reality" : ("tls".equals(value) ? "tls" : "");
                    break;
                case "sni":
                    node.sni = value;
                    break;
                case "type":
                    node.network = emptyTo(value, "tcp");
                    break;
                case "host":
                    node.host = value;
                    break;
                case "path":
                    node.path = value;
                    break;
                case "serviceName":
                    node.path = value;
                    break;
                case "flow":
                    node.flow = value;
                    break;
                case "alpn":
                    node.alpn = value;
                    break;
                case "fp":
                    node.fingerprint = value;
                    break;
                case "pbk":
                    node.publicKey = value;
                    break;
                case "sid":
                    node.shortId = value;
                    break;
                case "allowInsecure":
                    node.allowInsecure = "1".equals(value) || "true".equals(value);
                    break;
                default:
                    break;
            }
        }
        if (ProxyNode.PROTOCOL_VLESS.equals(protocol) && node.network.isEmpty()) {
            node.network = "tcp";
        }
        return node;
    }

    // ------------------------------------------------------------ shadowsocks

    private static ProxyNode parseShadowsocks(String body) throws Exception {
        String name = "";
        int hash = body.indexOf('#');
        if (hash >= 0) {
            name = urlDecode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        int q = body.indexOf('?');
        if (q >= 0) {
            body = body.substring(0, q); // plugin params are ignored (noted in docs)
        }

        String method;
        String password;
        String hostPort;

        int at = body.lastIndexOf('@');
        if (at >= 0) {
            // SIP002: ss://userinfo@host:port — userinfo is plain method:password
            // (url-encoded) or base64url(method:password).
            String userInfo = body.substring(0, at);
            hostPort = body.substring(at + 1);
            String decoded = userInfo.contains(":")
                    ? urlDecode(userInfo)
                    : new String(decodeBase64(userInfo), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("Bad ss user info");
            }
            method = decoded.substring(0, colon);
            password = decoded.substring(colon + 1);
        } else {
            // Legacy: ss://base64(method:password@host:port)
            String decoded = new String(decodeBase64(body), StandardCharsets.UTF_8);
            int atSign = decoded.lastIndexOf('@');
            if (atSign < 0) {
                throw new IllegalArgumentException("Bad legacy ss link");
            }
            String cred = decoded.substring(0, atSign);
            hostPort = decoded.substring(atSign + 1);
            int colon = cred.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("Bad legacy ss credentials");
            }
            method = cred.substring(0, colon);
            password = cred.substring(colon + 1);
        }

        HostPort hp = parseHostPort(hostPort, 8388);
        ProxyNode node = new ProxyNode();
        node.protocol = ProxyNode.PROTOCOL_SS;
        node.name = name;
        node.address = hp.host;
        node.port = hp.port;
        node.security = method;
        node.userId = password;
        node.network = "tcp";
        return node;
    }

    // ---------------------------------------------------------------- helpers

    private static class HostPort {
        String host;
        int port;
    }

    private static HostPort parseHostPort(String hostPort, int defaultPort) {
        HostPort hp = new HostPort();
        String hpStr = hostPort;
        int slash = hpStr.indexOf('/');
        if (slash >= 0) {
            hpStr = hpStr.substring(0, slash);
        }
        if (hpStr.startsWith("[")) { // IPv6
            int close = hpStr.indexOf(']');
            hp.host = hpStr.substring(1, close);
            hp.port = close + 1 < hpStr.length() && hpStr.charAt(close + 1) == ':'
                    ? parsePort(hpStr.substring(close + 2), defaultPort) : defaultPort;
        } else {
            int colon = hpStr.lastIndexOf(':');
            if (colon >= 0) {
                hp.host = hpStr.substring(0, colon);
                hp.port = parsePort(hpStr.substring(colon + 1), defaultPort);
            } else {
                hp.host = hpStr;
                hp.port = defaultPort;
            }
        }
        return hp;
    }

    private static int parsePort(String value, int defaultPort) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : defaultPort;
        } catch (Exception e) {
            return defaultPort;
        }
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /** Tolerant base64 decode: accepts url-safe alphabet and missing padding. */
    static byte[] decodeBase64(String input) {
        String normalized = input.trim().replace('-', '+').replace('_', '/').replaceAll("\\s", "");
        int pad = normalized.length() % 4;
        if (pad == 2) {
            normalized += "==";
        } else if (pad == 3) {
            normalized += "=";
        } else if (pad == 1) {
            throw new IllegalArgumentException("Invalid base64 length");
        }
        return Base64.decode(normalized, Base64.NO_WRAP);
    }

    private static String stableId(ProxyNode node) throws Exception {
        String material = node.protocol + "|" + node.address + "|" + node.port + "|"
                + node.userId + "|" + node.security + "|" + node.network + "|" + node.path;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", hash[i]));
        }
        return sb.toString();
    }
}
