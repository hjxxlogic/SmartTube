package com.liskovsoft.smartyoutubetv2.common.settingsserver;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.proxy.builtin.BuiltinProxyController;
import com.liskovsoft.smartyoutubetv2.common.proxy.builtin.ProxyConfigStore;
import com.liskovsoft.smartyoutubetv2.common.proxy.builtin.ProxyNode;
import com.liskovsoft.smartyoutubetv2.common.proxy.builtin.ShareLinkParser;
import com.liskovsoft.smartyoutubetv2.common.proxy.builtin.SubscriptionFetcher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The proxy section of the phone settings UI (first section of the general
 * framework). Accepts share links, a subscription URL, or a raw v2ray JSON
 * config; offers the stored nodes with an active-node picker.
 *
 * Secrets are write-only here: stored uuids/passwords and the stored raw
 * config are never rendered into the page.
 */
public class ProxySettingsSection implements SettingsSection {
    public static final String ID = "proxy";

    private final Context mContext;
    private final BuiltinProxyController mController;
    private final ProxyConfigStore mStore;

    public ProxySettingsSection(Context context) {
        mContext = context.getApplicationContext();
        mController = BuiltinProxyController.instance(mContext);
        mStore = mController.getStore();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getTitle() {
        return "Built-in proxy (v2ray)";
    }

    @Override
    public String renderHtml() {
        StringBuilder html = new StringBuilder();
        int mode = mController.getMode();
        html.append("<p class=\"status\">");
        html.append("Mode: <b>").append(modeName(mode)).append("</b>");
        if (mode == BuiltinProxyController.MODE_BUILTIN) {
            html.append(" &middot; Engine: <b>").append(escape(mController.getEngineState().name())).append("</b>");
            if (mController.isRawConfigMode()) {
                html.append(" &middot; Source: <b>raw JSON config</b>");
            } else {
                ProxyNode active = mController.getActiveNode();
                html.append(" &middot; Active node: <b>")
                        .append(active == null ? "none" : escape(active.getDisplayName()))
                        .append("</b>");
            }
        }
        html.append("</p>");

        html.append("<label>Share links (one per line: vmess://, vless://, trojan://, ss://)")
                .append("<textarea name=\"links\" rows=\"4\" placeholder=\"Paste link(s) here\"></textarea></label>");
        html.append("<label>or subscription URL")
                .append("<input type=\"url\" name=\"suburl\" placeholder=\"https://example.com/subscription\"></label>");
        html.append("<label>or raw v2ray JSON config")
                .append("<textarea name=\"rawjson\" rows=\"4\" placeholder=\"{ &quot;outbounds&quot;: [...] }\"></textarea></label>");
        html.append("<p class=\"hint\">Fill in ONE of the three fields. Saving links or a subscription "
                + "replaces the current node list. A stored raw config is never shown here again.</p>");

        List<ProxyNode> nodes = mController.getNodes();
        if (!nodes.isEmpty() && !mController.isRawConfigMode()) {
            ProxyNode active = mController.getActiveNode();
            html.append("<fieldset><legend>Active node</legend>");
            for (ProxyNode node : nodes) {
                String checked = active != null && node.id.equals(active.id) ? " checked" : "";
                html.append("<label class=\"radio\"><input type=\"radio\" name=\"activeNode\" value=\"")
                        .append(escape(node.id)).append("\"").append(checked).append("> ")
                        .append(escape(node.getDisplayName())).append("</label>");
            }
            html.append("</fieldset>");
        }

        html.append("<label class=\"check\"><input type=\"checkbox\" name=\"enableBuiltin\"")
                .append(mode == BuiltinProxyController.MODE_BUILTIN ? " checked" : "")
                .append("> Enable built-in proxy (fail-closed: all app traffic via the engine)</label>");
        return html.toString();
    }

    @Override
    public Result handleSave(Map<String, String> fields) {
        String links = trim(fields.get("links"));
        String subUrl = trim(fields.get("suburl"));
        String rawJson = trim(fields.get("rawjson"));
        String activeNode = trim(fields.get("activeNode"));
        boolean enableBuiltin = fields.containsKey("enableBuiltin");

        boolean configChanged = false;
        try {
            if (!rawJson.isEmpty()) {
                // Validate before storing: adaptRawConfig throws on bad JSON
                // or a config without outbounds.
                com.liskovsoft.smartyoutubetv2.common.proxy.builtin.V2RayConfigBuilder.adaptRawConfig(rawJson);
                mStore.saveRawConfig(rawJson);
                configChanged = true;
            } else if (!links.isEmpty() || !subUrl.isEmpty()) {
                List<ProxyNode> nodes = null;
                if (!subUrl.isEmpty()) {
                    SubscriptionFetcher.Result fetched = SubscriptionFetcher.fetch(subUrl);
                    if (fetched.error != null) {
                        return new Result(false, "Subscription failed: " + fetched.error);
                    }
                    nodes = fetched.nodes;
                }
                if (!links.isEmpty()) {
                    nodes = ShareLinkParser.parseMany(links);
                    if (nodes.isEmpty()) {
                        return new Result(false, "No valid share links found in the pasted text");
                    }
                }
                mStore.saveRawConfig(null);
                mStore.saveNodes(nodes);
                mController.setActiveNodeId(nodes.get(0).id);
                configChanged = true;
            } else if (!activeNode.isEmpty()) {
                mController.setActiveNodeId(activeNode);
                configChanged = true;
            }
        } catch (Exception e) {
            return new Result(false, "Invalid configuration: " + e.getClass().getSimpleName());
        }

        if (enableBuiltin && mController.getMode() != BuiltinProxyController.MODE_BUILTIN) {
            mController.setMode(BuiltinProxyController.MODE_BUILTIN);
        } else if (mController.getMode() == BuiltinProxyController.MODE_BUILTIN && configChanged) {
            mController.restartEngine();
        }

        if (mController.getMode() == BuiltinProxyController.MODE_BUILTIN) {
            String test = runConnectionTest();
            return new Result(true, "Saved. " + test);
        }
        return new Result(true, configChanged
                ? "Saved. Enable the built-in proxy to start using it."
                : "Nothing changed.");
    }

    private String runConnectionTest() {
        // Give the engine a moment to come up after a (re)start.
        final String[] detail = new String[1];
        final boolean[] ok = new boolean[1];
        CountDownLatch latch = new CountDownLatch(1);
        // Poll briefly for the engine, then run the spec test through SOCKS.
        new Thread(() -> {
            for (int i = 0; i < 20 && mController.getEngineState()
                    != com.liskovsoft.smartyoutubetv2.common.proxy.builtin.V2RayEngine.State.RUNNING; i++) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            mController.testConnection((success, message) -> {
                ok[0] = success;
                detail[0] = message;
                latch.countDown();
            });
        }).start();
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return detail[0] == null
                ? "Connection test timed out."
                : (ok[0] ? "Connection test OK: " : "Connection test FAILED: ") + detail[0];
    }

    private String modeName(int mode) {
        switch (mode) {
            case BuiltinProxyController.MODE_EXTERNAL:
                return "External proxy";
            case BuiltinProxyController.MODE_BUILTIN:
                return "Built-in v2ray";
            default:
                return "Off";
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
