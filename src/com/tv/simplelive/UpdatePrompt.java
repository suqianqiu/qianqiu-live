package com.tv.simplelive;

import android.app.*;
import android.content.Intent;

/** Attached only while the home page is resumed; playback is never interrupted by a prompt. */
final class UpdatePrompt implements AppUpdate.Listener {
    private final Activity activity;
    private final AppUpdate update;
    private boolean active;
    private AlertDialog dialog;
    UpdatePrompt(Activity activity){this.activity=activity;update=AppUpdate.get(activity);}
    void resume(){active=true;update.add(this);update.check(false);}
    void pause(){active=false;update.remove(this);if(dialog!=null){dialog.dismiss();dialog=null;}}
    public void changed(AppUpdate.State state){
        if(!active||activity.isFinishing()||!update.shouldPrompt())return;
        update.markPrompted();
        dialog=new AlertDialog.Builder(activity).setTitle("发现新版本 "+state.release.version)
            .setMessage(state.release.notes+"\n\n可以现在查看更新，也可稍后从版本更新入口升级。")
            .setPositiveButton("查看更新",(d,w)->activity.startActivity(new Intent(activity,UpdateActivity.class)))
            .setNegativeButton("稍后",null).show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
}
