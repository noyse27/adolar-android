package net.polze.adolarradio.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Independent scrobble delivery queue for local files only, shared by
 * whichever scrobble destination is currently connected - Last.fm or a
 * self-hosted Adolar Scrobbler, never both (see {@link #destination}).
 * The table name stays "lastfm_scrobbles" for migration simplicity; it is
 * no longer Last.fm-exclusive.
 */
@Entity(tableName = "lastfm_scrobbles", indices = @Index("state"))
public class LastFmScrobble {
    public static final String PENDING = "pending";
    public static final String SENDING = "sending";

    public static final String DEST_LASTFM = "lastfm";
    public static final String DEST_ADOLAR_SCROBBLER = "adolar_scrobbler";

    @PrimaryKey @NonNull public String eventId = "";
    public long localTrackId;
    @NonNull public String artist = "";
    @NonNull public String title = "";
    @NonNull public String album = "";
    public long startedAtUtc;
    public int durationSeconds;
    @NonNull public String state = PENDING;
    public int attempts;
    public String lastError;
    public Long nextRetryAt;
    @NonNull public String destination = DEST_LASTFM;
}
