package com.eagleseye.liquidglass.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.WindowManager;
import android.view.WindowMetrics;

public class Utils {

    public static int getDeviceWidthPx(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager wm = context.getSystemService(WindowManager.class);
            if (wm != null) {
                WindowMetrics metrics = wm.getCurrentWindowMetrics();
                Rect bounds = metrics.getBounds();
                return bounds.width();
            }
        }
        Resources res = context.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        return dm.widthPixels;
    }

    public static float dp2px(Resources res, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, res.getDisplayMetrics());
    }
}
