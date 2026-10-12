package cn.flashcard.app;

import androidx.datastore.preferences.core.Preferences;
/** Bridges the stable Java API of the multiplatform DataStore preference key factory. */
public final class PreferenceKeys {
    private PreferenceKeys() {}
    public static final Preferences.Key<String> SNAPSHOT = androidx.datastore.preferences.core.PreferencesKeys.stringKey("snapshot");
}
