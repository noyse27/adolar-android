package net.polze.adolarradio.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                LibraryRoot.class,
                LocalTrack.class,
                TrackState.class,
                LocalPlaylist.class,
                PlaylistTrack.class,
                SyncOutboxEntry.class,
                SyncReceipt.class
                , LastFmScrobble.class
        },
        version = 6,
        exportSchema = true
)
public abstract class LocalLibraryDatabase extends RoomDatabase {
    private static volatile LocalLibraryDatabase instance;

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `playlists` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT NOT NULL, `type` TEXT NOT NULL, "
                    + "`filterJson` TEXT NOT NULL, `sort` TEXT NOT NULL, "
                    + "`isSystem` INTEGER NOT NULL, `systemKey` TEXT, "
                    + "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "`index_playlists_systemKey` ON `playlists` (`systemKey`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_name` "
                    + "ON `playlists` (`name`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `playlist_tracks` ("
                    + "`playlistId` INTEGER NOT NULL, `localTrackId` INTEGER NOT NULL, "
                    + "`position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`playlistId`, `localTrackId`), "
                    + "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) "
                    + "ON UPDATE NO ACTION ON DELETE CASCADE, "
                    + "FOREIGN KEY(`localTrackId`) REFERENCES `local_tracks`(`id`) "
                    + "ON UPDATE NO ACTION ON DELETE CASCADE)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "`index_playlist_tracks_playlistId_position` "
                    + "ON `playlist_tracks` (`playlistId`, `position`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS "
                    + "`index_playlist_tracks_localTrackId` "
                    + "ON `playlist_tracks` (`localTrackId`)");
            seedSystemPlaylists(database);
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` ("
                    + "`eventId` TEXT PRIMARY KEY NOT NULL, `kind` TEXT NOT NULL, "
                    + "`localTrackId` INTEGER, `payloadJson` TEXT NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL, `startedAtUtc` INTEGER NOT NULL, "
                    + "`state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, "
                    + "`lastError` TEXT, `nextRetryAt` INTEGER)");
            database.execSQL("CREATE INDEX IF NOT EXISTS "
                    + "`index_sync_outbox_state_nextRetryAt` "
                    + "ON `sync_outbox` (`state`, `nextRetryAt`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `sync_receipts` ("
                    + "`eventId` TEXT PRIMARY KEY NOT NULL, "
                    + "`confirmedAt` INTEGER NOT NULL)");
        }
    };

    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `track_state` ADD COLUMN `tagPlayCount` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `track_state` ADD COLUMN `tagPlayCountImported` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `track_state` ADD COLUMN `lastFmPlayCount` INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `track_state` ADD COLUMN `lastFmCheckedAt` INTEGER");
            database.execSQL("CREATE TABLE IF NOT EXISTS `lastfm_scrobbles` ("
                    + "`eventId` TEXT NOT NULL, `localTrackId` INTEGER NOT NULL, "
                    + "`artist` TEXT NOT NULL, `title` TEXT NOT NULL, `album` TEXT NOT NULL, "
                    + "`startedAtUtc` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, "
                    + "`state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, "
                    + "`nextRetryAt` INTEGER, PRIMARY KEY(`eventId`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_lastfm_scrobbles_state` "
                    + "ON `lastfm_scrobbles` (`state`)");
        }
    };

    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            // lastfm_scrobbles becomes a shared outbox for whichever scrobble
            // destination (Last.fm or Adolar Scrobbler) is currently
            // connected - see LastFmScrobble.destination.
            database.execSQL("ALTER TABLE `lastfm_scrobbles` ADD COLUMN `destination` "
                    + "TEXT NOT NULL DEFAULT 'lastfm'");
        }
    };

    public abstract LibraryDao libraryDao();

    public static LocalLibraryDatabase get(Context context) {
        if (instance == null) {
            synchronized (LocalLibraryDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            LocalLibraryDatabase.class,
                            "adolar-next-library.db"
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                            .addCallback(new Callback() {
                                @Override
                                public void onOpen(@NonNull SupportSQLiteDatabase database) {
                                    seedSystemPlaylists(database);
                                }
                            })
                            .build();
                }
            }
        }
        return instance;
    }

    private static void seedSystemPlaylists(SupportSQLiteDatabase database) {
        long now = System.currentTimeMillis();
        seed(database, "Favoriten", "favorites", now);
        seed(database, "Kürzlich hinzugefügt", "recently_added", now);
        seed(database, "Häufig gespielt", "most_played", now);
        seed(database, "Kürzlich gespielt", "recently_played", now);
        seed(database, "Selten gespielt", "least_played", now);
        seed(database, "Nie gespielt", "never_played", now);
    }

    private static void seed(
            SupportSQLiteDatabase database, String name, String key, long now
    ) {
        database.execSQL(
                "INSERT OR IGNORE INTO playlists "
                        + "(name,type,filterJson,sort,isSystem,systemKey,createdAt,updatedAt) "
                        + "VALUES (?, 'system', ?, 'artist', 1, ?, ?, ?)",
                new Object[]{name, "{\"system\":\"" + key + "\"}", key, now, now}
        );
    }
}
