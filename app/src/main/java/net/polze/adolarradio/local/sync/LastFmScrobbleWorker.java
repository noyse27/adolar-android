package net.polze.adolarradio.local.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import net.polze.adolarradio.local.LastFmScrobble;
import net.polze.adolarradio.local.LastFmSessionStore;
import net.polze.adolarradio.local.LibraryDao;
import net.polze.adolarradio.local.LocalLibraryDatabase;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Sends local-file scrobbles straight to Last.fm, independently of Adolar Web. */
public final class LastFmScrobbleWorker extends Worker {
    private static final String UNIQUE="lastfm-local-scrobbles";
    public LastFmScrobbleWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}

    public static void enqueue(Context context) {
        Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(LastFmScrobbleWorker.class)
                .setConstraints(constraints).setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE,request);
    }

    @NonNull @Override public Result doWork() {
        Context context=getApplicationContext(); String session=LastFmSessionStore.session(context);
        if(session.isEmpty()) return Result.success();
        LibraryDao dao=LocalLibraryDatabase.get(context).libraryDao();
        List<LastFmScrobble> rows=dao.getSendableLastFmScrobbles(
                LastFmScrobble.DEST_LASTFM, System.currentTimeMillis(), 50);
        if(rows.isEmpty()) return Result.success();
        for(LastFmScrobble row:rows) dao.markLastFmSending(row.eventId);
        try {
            List<Boolean> accepted=LastFmApi.scrobble(rows,session);
            long retry=System.currentTimeMillis()+60_000L;
            for(int i=0;i<rows.size();i++) {
                LastFmScrobble row=rows.get(i);
                if(accepted.get(i)) dao.deleteLastFmScrobble(row.eventId);
                else dao.retryLastFmScrobble(row.eventId,"ignored_by_lastfm",retry);
            }
            if(rows.size()==50) enqueue(context);
            return Result.success();
        } catch(Exception error) {
            long retry=System.currentTimeMillis()+60_000L;
            for(LastFmScrobble row:rows) dao.retryLastFmScrobble(row.eventId,String.valueOf(error.getMessage()),retry);
            return Result.retry();
        }
    }
}
