package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * System notices for the main process: the TV booted, or this app was updated. Not exported. Direct-boot aware only to
 * hear the first boot notice (a minute and more before BOOT_COMPLETED on a slow TV): then it touches no files, it only
 * asks for the home screen check, a job that runs once Android lets the app read its files.
 */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            ModeJob.schedule(context, ModeJob.HOME, null, 0);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
        }
    }
}
