package com.liskovsoft.smartyoutubetv2.common.settingsserver;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of phone-configurable setting sections. The proxy section is the
 * first — and currently only — registered section. Future sections register
 * here and appear on the config page automatically.
 */
public class SettingsRegistry {
    private final List<SettingsSection> mSections = new ArrayList<>();

    public SettingsRegistry(Context context) {
        register(new ProxySettingsSection(context));
    }

    public void register(SettingsSection section) {
        mSections.add(section);
    }

    public List<SettingsSection> getSections() {
        return mSections;
    }

    public SettingsSection findById(String id) {
        for (SettingsSection section : mSections) {
            if (section.getId().equals(id)) {
                return section;
            }
        }
        return null;
    }
}
