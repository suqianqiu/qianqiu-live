package com.tv.simplelive;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;

/** Explicit TV remote focus; independent of manufacturer theme tints and elevation. */
final class TvButtonStyle {
    static android.app.AlertDialog show(android.app.AlertDialog.Builder builder){
        android.app.AlertDialog dialog=builder.show();apply(dialog);return dialog;
    }
    static void apply(android.app.AlertDialog dialog){
        for(int which:new int[]{android.app.AlertDialog.BUTTON_POSITIVE,android.app.AlertDialog.BUTTON_NEGATIVE,android.app.AlertDialog.BUTTON_NEUTRAL}){
            Button button=dialog.getButton(which);if(button!=null)apply(button);
        }
    }
    private static int dp(Button button,int value){return Math.round(value*button.getResources().getDisplayMetrics().density);}
    private static GradientDrawable shape(Button button,int fill,int border,int width){
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(fill);
        drawable.setCornerRadius(dp(button,6));drawable.setStroke(dp(button,width),border);return drawable;
    }
    static void apply(Button button){
        StateListDrawable backgrounds=new StateListDrawable();
        backgrounds.addState(new int[]{-android.R.attr.state_enabled},shape(button,0xFF202E38,0xFF3E505C,1));
        backgrounds.addState(new int[]{android.R.attr.state_pressed},shape(button,0xFFC4FFF0,0xFFFFFFFF,3));
        backgrounds.addState(new int[]{android.R.attr.state_focused},shape(button,0xFF81E2CB,0xFFFFFFFF,3));
        backgrounds.addState(new int[]{},shape(button,0xFF2B3C48,0xFF536571,1));
        button.setBackgroundTintList(null);button.setStateListAnimator(null);
        button.setBackground(new InsetDrawable(backgrounds,dp(button,4)));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_focused},new int[]{}},new int[]{0xFF87959D,0xFF10222D,0xFF10222D,0xFFFFFFFF}));
        button.setTypeface(null,Typeface.BOLD);button.setPadding(dp(button,12),0,dp(button,12),0);
    }
}
