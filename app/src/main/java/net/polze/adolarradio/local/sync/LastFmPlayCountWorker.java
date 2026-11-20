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

import net.polze.adolarradio.local.LastFmSessionStore;
import net.polze.adolarradio.local.LibraryDao;
import net.polze.adolarradio.local.LocalLibraryDatabase;
import net.polze.adolarradio.local.LocalTrack;

import java.util.List;

/** Slowly fills the local Last.fm count cache without blocking library scans. */
public final class LastFmPlayCountWorker extends Worker {
    private static final String WORK = "lastfm-local-playcounts";
    private static final int BATCH = 20;
    private static final long MAX_AGE = 7L * 24 * 60 * 60 * 1000;

    public LastFmPlayCountWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    public static void enqueue(Context context) {
        Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(LastFmPlayCountWorker.class)
                .setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,request);
    }

    @NonNull @Override public Result doWork() {
        Context context=getApplicationContext();
        String user=LastFmSessionStore.user(context);
        if(user.isEmpty() || !LastFmApi.configured()) return Result.success();
        LibraryDao dao=LocalLibraryDatabase.get(context).libraryDao();
        List<LocalTrack> tracks=dao.getTracksNeedingLastFmCount(System.currentTimeMillis()-MAX_AGE,BATCH);
        long checked=System.currentTimeMillis();
        try {
            for(LocalTrack track:tracks) {
                int count=LastFmApi.userPlayCount(user,track.artist,track.title);
                dao.importLastFmPlayCount(track.id,count,checked);
            }
            if(tracks.size()==BATCH) enqueue(context);
            return Result.success();
        } catch(Exception error) { return Result.retry(); }
    }
}
