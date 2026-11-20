package net.polze.adolarradio.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Keystore-backed Adolar Scrobbler session and public username. Mirrors
 * {@link LastFmSessionStore} - a separate keystore file for the second,
 * mutually-exclusive scrobble destination (see NextActivity's login flow:
 * logging into one always clears the other).
 */
public final class AdolarScrobblerSessionStore {
    private static final String FILE = "adolar_scrobbler_direct_session";
    private static final String SESSION = "session";
    private static final String USER = "user";

    private AdolarScrobblerSessionStore() { }

    private static SharedPreferences prefs(Context context) throws Exception {
        MasterKey key = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        return EncryptedSharedPreferences.create(
                context, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    public static void set(Context context, String user, String session) throws Exception {
        if (!prefs(context).edit().putString(USER, user).putString(SESSION, session).commit())
            throw new IllegalStateException("Adolar Scrobbler session could not be stored");
    }

    public static String session(Context context) {
        try { return prefs(context).getString(SESSION, ""); } catch (Exception e) { return ""; }
    }

    public static String user(Context context) {
        try { return prefs(context).getString(USER, ""); } catch (Exception e) { return ""; }
    }

    public static void clear(Context context) {
        try { prefs(context).edit().clear().commit(); } catch (Exception ignored) { }
    }
}
