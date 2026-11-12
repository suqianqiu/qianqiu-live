package com.tv.simplelive;
import android.content.*;

/** Explicit private PackageInstaller callback; unrelated broadcasts are rejected. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if((context.getPackageName()+".UPDATE_RESULT").equals(intent.getAction()))AppUpdate.get(context).result(intent);}
}
