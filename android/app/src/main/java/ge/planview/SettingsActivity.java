package ge.planview;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private interface Pick { void on(int i); }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        Ui.edgeToEdge(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 12), pad, pad);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView t = Ui.label(this, "Settings", 26, Ui.TEXT, true);
        t.setPadding(Ui.dp(this, 8), 0, 0, 0);
        head.addView(t);
        col.addView(head);

        section(col, "Theme", null, new String[]{"System", "Light", "Dark"}, Store.theme(this), i -> {
            if (i != Store.theme(this)) { Store.setTheme(this, i); recreate(); }
        });
        section(col, "Measurement units", "Lengths, areas and calibration input",
                Units.NAMES, Store.unit(this), i -> { Store.setUnit(this, i); Units.unit = i; });
        section(col, "Hairlines", "Draw every line at the thinnest weight, like Hairline in ArchiCAD",
                new String[]{"Off", "On"}, Store.hairline(this) ? 1 : 0, i -> Store.setHairline(this, i == 1));
        section(col, "Default view", null, new String[]{"Continuous", "One sheet"},
                Store.continuous(this) ? 0 : 1, i -> Store.setContinuous(this, i == 0));

        TextView lic = Ui.label(this, "Open-source licenses", 15, Ui.TEXT, true);
        lic.setGravity(Gravity.CENTER_VERTICAL);
        lic.setMinHeight(Ui.dp(this, 56));
        lic.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        lic.setBackground(Ui.rippled(this, Ui.SURFACE | 0xFF000000, 24));
        lic.setClickable(true);
        lic.setOnClickListener(v -> showLicenses());
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = Ui.dp(this, 14);
        col.addView(lic, ll);

        TextView ver = Ui.label(this, "Calque " + versionName() + " \u00B7 PDF engine: PDFium", 12, Ui.DIM, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, Ui.dp(this, 16), 0, 0);
        col.addView(ver);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col);
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(0, in.getSystemWindowInsetTop(), 0, in.getSystemWindowInsetBottom());
            return in;
        });
        setContentView(sv);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable t) { return ""; }
    }

    private void showLicenses() {
        String text;
        try (java.io.InputStream in = getAssets().open("licenses.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            text = out.toString("UTF-8");
        } catch (Throwable t) {
            text = "Licenses could not be loaded.";
        }
        TextView tv = Ui.label(this, text, 11, Ui.TEXT, false);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this).setTitle("Open-source licenses").setView(sv)
                .setPositiveButton("Close", null).show();
    }

    private void section(LinearLayout col, String title, String hint, String[] options, int selected, Pick pick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p);
        card.setBackground(Ui.shape(Ui.SURFACE | 0xFF000000, Ui.dp(this, 24)));
        card.addView(Ui.label(this, title, 16, Ui.TEXT, true));
        if (hint != null) card.addView(Ui.label(this, hint, 13, Ui.DIM, false));

        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(Ui.shape(Ui.CHIP, Ui.dp(this, 22)));
        int sp = Ui.dp(this, 3);
        seg.setPadding(sp, sp, sp, sp);
        final TextView[] btns = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView bt = Ui.button(this, options[i], 0, Ui.TEXT);
            bt.setTextSize(14);
            bt.setPadding(0, 0, 0, 0);
            bt.setMinHeight(Ui.dp(this, 38));
            btns[i] = bt;
            bt.setOnClickListener(v -> {
                for (int j = 0; j < btns.length; j++) Ui.segment(btns[j], j == idx);
                pick.on(idx);
            });
            Ui.segment(bt, i == selected);
            seg.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Ui.dp(this, 12);
        card.addView(seg, sl);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = Ui.dp(this, 14);
        col.addView(card, cl);
    }
}
