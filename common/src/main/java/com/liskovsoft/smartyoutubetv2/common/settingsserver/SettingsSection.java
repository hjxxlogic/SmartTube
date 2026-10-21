package com.liskovsoft.smartyoutubetv2.common.settingsserver;

import java.util.Map;

/**
 * One section of the phone-based settings UI.
 *
 * The settings web framework is deliberately generic (owner spec): a section
 * knows how to render its own HTML form fragment and how to persist its own
 * fields. New sections plug in through {@link SettingsRegistry} without
 * touching the server, the QR flow or the hosting activity.
 *
 * Contract: renderHtml() must never include secret values (passwords, uuids,
 * tokens); form fields for secrets are write-only.
 */
public interface SettingsSection {

    class Result {
        public final boolean success;
        public final String message;

        public Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    String getId();

    String getTitle();

    /** HTML fragment for this section's form (without the surrounding form tag). */
    String renderHtml();

    /** Persist the submitted fields. Runs on the web server thread. */
    Result handleSave(Map<String, String> fields);
}
