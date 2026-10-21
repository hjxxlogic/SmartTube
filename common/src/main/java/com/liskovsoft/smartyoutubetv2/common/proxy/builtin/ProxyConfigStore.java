package com.liskovsoft.smartyoutubetv2.common.proxy.builtin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores the built-in proxy configuration in app-private files:
 * files/builtin_proxy/nodes.json and files/builtin_proxy/raw_config.json.
 * These files contain SECRETS (uuids / passwords). They never leave the app
 * sandbox, are never logged, and are never rendered back to the config page.
 */
public class ProxyConfigStore {
    private static final String DIR_NAME = "builtin_proxy";
    private static final String NODES_FILE = "nodes.json";
    private static final String RAW_FILE = "raw_config.json";

    private final File mDir;

    public ProxyConfigStore(Context context) {
        mDir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        mDir.mkdirs();
    }

    public List<ProxyNode> loadNodes() {
        List<ProxyNode> result = new ArrayList<>();
        String content = readFile(NODES_FILE);
        if (content == null || content.isEmpty()) {
            return result;
        }
        try {
            JSONArray array = new JSONObject(content).optJSONArray("nodes");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    result.add(ProxyNode.fromJson(array.getJSONObject(i)));
                }
            }
        } catch (Exception ignored) {
            // Corrupt store: behave as if empty. Do not log content (secrets).
        }
        return result;
    }

    public void saveNodes(List<ProxyNode> nodes) {
        try {
            JSONArray array = new JSONArray();
            for (ProxyNode node : nodes) {
                array.put(node.toJson());
            }
            writeFile(NODES_FILE, new JSONObject().put("nodes", array).toString());
        } catch (Exception ignored) {
            // Persistence failure surfaces through status (no nodes found).
        }
    }

    public String loadRawConfig() {
        return readFile(RAW_FILE);
    }

    public void saveRawConfig(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(mDir, RAW_FILE).delete();
        } else {
            writeFile(RAW_FILE, rawJson.trim());
        }
    }

    public boolean hasAnyConfig() {
        String raw = loadRawConfig();
        return (raw != null && !raw.isEmpty()) || !loadNodes().isEmpty();
    }

    private String readFile(String name) {
        File file = new File(mDir, name);
        if (!file.exists()) {
            return null;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFile(String name, String content) {
        File file = new File(mDir, name);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Surfaced indirectly: a later load finds no config.
        }
    }
}
