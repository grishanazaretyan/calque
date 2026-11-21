package ge.planview;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

/** Colours (light / dark, with the phone's own accent colour on Android 12+) and small view helpers. */
final class Ui {
    static boolean night;
    static int themePref;
    static int BG, SURFACE, CHIP, TEXT, DIM, ACCENT, ON_ACCENT, RIPPLE, OUTLINE;
    /** Measuring marks keep one strong colour that reads on any drawing. */
    static final int MARK = 0xFFFF6A13;

    static void init(Context c) {
        themePref = Store.theme(c);
        Units.unit = Store.unit(c);
        night = themePref == 2 || (themePref == 0
                && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        if (night) {
            BG = 0xFF111317; SURFACE = 0xF21E2126; CHIP = 0xFF2C3037; TEXT = 0xFFECEEF2; DIM = 0xFF9BA1AA;
            ACCENT = 0xFFFFB68A; ON_ACCENT = 0xFF3A1A00; RIPPLE = 0x33FFFFFF; OUTLINE = 0x1FFFFFFF;
        } else {
            BG = 0xFFE7E9EE; SURFACE = 0xF5FFFFFF; CHIP = 0xFFEDEFF3; TEXT = 0xFF181A1E; DIM = 0xFF5D646D;
            ACCENT = 0xFFB3480A; ON_ACCENT = 0xFFFFFFFF; RIPPLE = 0x22000000; OUTLINE = 0x14000000;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                ACCENT = c.getColor(night ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
                ON_ACCENT = c.getColor(night ? android.R.color.system_accent1_800 : android.R.color.system_accent1_0);
            } catch (Throwable ignored) { }
        }
    }

    /** Applies the chosen theme; call before super.onCreate. */
    static void apply(Activity a) {
        init(a);
        a.setTheme(night ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }

    /** Selected / unselected look of a segmented-control button. */
    static void segment(TextView t, boolean on) {
        Context c = t.getContext();
        t.setTextColor(on ? ON_ACCENT : TEXT);
        t.setBackground(on ? shape(ACCENT, dp(c, 19)) : rippled(c, 0, 19));
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Draws the app behind the status and navigation bars. */
    @SuppressWarnings("deprecation")
    static void edgeToEdge(Activity a) {
        Window w = a.getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int scrim = (BG & 0x00FFFFFF) | 0xA6000000;
        w.setStatusBarColor(scrim);
        w.setNavigationBarColor(Build.VERSION.SDK_INT >= 29 ? 0x00000000 : scrim);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.getDecorView(); // creates the window's views; without it the next call crashes on Android 11+
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController ic = w.getInsetsController();
            if (ic != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(night ? 0 : light, light);
            }
        } else {
            int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!night) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    static GradientDrawable shape(int fill, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Drawable rippled(Context c, int fill, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape(fill, dp(c, radiusDp)), null);
    }

    /** A floating rounded surface (bars, cards). */
    static void floating(View v, float radiusDp) {
        GradientDrawable g = shape(SURFACE, dp(v.getContext(), radiusDp));
        g.setStroke(Math.max(1, dp(v.getContext(), 0.75f)), OUTLINE);
        v.setBackground(g);
        v.setElevation(dp(v.getContext(), 6));
    }

    static TextView label(Context c, String text, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView button(Context c, String text, int fill, int textColor) {
        TextView t = label(c, text, 15, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setMinHeight(dp(c, 44));
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        t.setBackground(rippled(c, fill, 22));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static ImageButton icon(Context c, int res, String description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setColorFilter(TEXT);
        b.setContentDescription(description);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask));
        int p = dp(c, 10);
        b.setPadding(p, p, p, p);
        return b;
    }

    /** "A1" for standard sheets, otherwise the size in millimetres. */
    static String paper(float wPt, float hPt) {
        int w = Math.round(wPt * 25.4f / 72f), h = Math.round(hPt * 25.4f / 72f);
        int lo = Math.min(w, h), hi = Math.max(w, h);
        int[][] iso = {{841, 1189}, {594, 841}, {420, 594}, {297, 420}, {210, 297}, {148, 210}};
        for (int i = 0; i < iso.length; i++) {
            if (Math.abs(lo - iso[i][0]) <= 3 && Math.abs(hi - iso[i][1]) <= 3) {
                return "A" + i + (w > h ? " landscape" : " portrait");
            }
        }
        return w + " × " + h + " mm";
    }
}
