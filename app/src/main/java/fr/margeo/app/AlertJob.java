package fr.margeo.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Tâches de fond : alertes (toutes les heures) et mises à jour (toutes les 6 heures). */
public class AlertJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            if (params.getJobId() == Updater.JOB_ID) Updater.verifierEnFond(getApplicationContext());
            else Alertes.lancer(getApplicationContext());
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
