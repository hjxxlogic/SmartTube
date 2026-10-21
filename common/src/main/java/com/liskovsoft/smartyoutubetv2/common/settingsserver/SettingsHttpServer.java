package com.liskovsoft.smartyoutubetv2.common.settingsserver;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Tiny embedded web server for phone-based configuration.
 *
 * Lifecycle is owned by {@link PhoneConfigManager}: the server exists only
 * while the QR screen is open (hard stop after 5 minutes), and every route
 * requires the one-time token from the QR code. The token is consumed by the
 * first successful save.
 */
public class SettingsHttpServer extends NanoHTTPD {
    private final SettingsRegistry mRegistry;
    private final PhoneConfigManager mManager;

    public SettingsHttpServer(int port, SettingsRegistry registry, PhoneConfigManager manager) {
        super(port);
        mRegistry = registry;
        mManager = manager;
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, String> params = new HashMap<>(session.getParms());
        if (session.getMethod() == Method.POST) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                params.putAll(session.getParms());
            } catch (IOException | ResponseException e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT, "Failed to read the form");
            }
        }

        String token = params.get("t");
        if (token == null || !token.equals(mManager.getToken()) || mManager.isConsumed()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_HTML,
                    page("Session closed", "<p>This configuration session has ended. "
                            + "Open the QR screen on your TV again to start a new one.</p>"));
        }

        String uri = session.getUri();
        if ("/save".equals(uri) && session.getMethod() == Method.POST) {
            return handleSave(params);
        }
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderPage());
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
    }

    private Response handleSave(Map<String, String> params) {
        String sectionId = params.get("section");
        SettingsSection section = sectionId == null ? null : mRegistry.findById(sectionId);
        if (section == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_HTML,
                    page("Error", "<p>Unknown settings section.</p>"));
        }
        SettingsSection.Result result = section.handleSave(params);
        if (result.success) {
            mManager.onSaveSuccess(result.message);
        }
        String back = result.success ? "" : "<p><a href=\"/?t=" + mManager.getToken() + "\">Back to the form</a></p>";
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML,
                page(result.success ? "Saved" : "Not saved",
                        "<p>" + ProxySettingsSection.escape(result.message) + "</p>" + back));
    }

    private String renderPage() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>SmartTube settings</title><style>")
                .append("body{font-family:sans-serif;margin:16px;line-height:1.45}")
                .append("label{display:block;margin:12px 0}textarea,input[type=url]{width:100%;box-sizing:border-box;padding:8px}")
                .append("fieldset{margin:12px 0}.radio,.check{display:block;margin:6px 0}")
                .append(".hint{color:#666;font-size:.9em}.status{background:#f2f2f2;padding:8px;border-radius:6px}")
                .append("button{font-size:1.05em;padding:10px 22px;margin-top:10px}")
                .append("</style></head><body><h1>SmartTube settings</h1>");
        for (SettingsSection section : mRegistry.getSections()) {
            html.append("<h2>").append(ProxySettingsSection.escape(section.getTitle())).append("</h2>");
            html.append("<form method=\"POST\" action=\"/save?t=").append(mManager.getToken()).append("\">");
            html.append("<input type=\"hidden\" name=\"section\" value=\"")
                    .append(ProxySettingsSection.escape(section.getId())).append("\">");
            html.append(section.renderHtml());
            html.append("<button type=\"submit\">Save</button></form>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + title + "</title></head><body><h1>" + title + "</h1>"
                + body + "</body></html>";
    }
}
