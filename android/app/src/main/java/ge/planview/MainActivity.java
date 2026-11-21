package ge.planview;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Start screen: recent drawings and one button to open a PDF. */
public class MainActivity extends Activity {
    private static final int PICK = 1;
    private LinearLayout recentBox;
    private int themeAtStart;

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        Ui.edgeToEdge(this);
        themeAtStart = Ui.themePref;
        int pad = Ui.dp(this, 20);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, Ui.dp(this, 36), pad, Ui.dp(this, 110));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.label(this, "Calque", 34, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageButton gear = Ui.icon(this, R.drawable.ic_tune, "Settings");
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        head.addView(gear, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        col.addView(head);
        TextView sub = Ui.label(this, "Drawings, fast and to scale", 15, Ui.DIM, false);
        sub.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 28));
        col.addView(sub);

        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(recentBox);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout open = new LinearLayout(this);
        open.setGravity(Gravity.CENTER);
        open.setBackground(Ui.rippled(this, Ui.ACCENT, 30));
        open.setElevation(Ui.dp(this, 6));
        open.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 26), 0);
        open.setClickable(true);
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(Ui.ON_ACCENT);
        open.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView ot = Ui.label(this, "Open PDF", 16, Ui.ON_ACCENT, true);
        ot.setPadding(Ui.dp(this, 10), 0, 0, 0);
        open.addView(ot);
        open.setOnClickListener(v -> pick());
        final FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-2, Ui.dp(this, 60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ol.bottomMargin = Ui.dp(this, 24);
        root.addView(open, ol);

        root.setOnApplyWindowInsetsListener((v, in) -> {
            sv.setPadding(0, in.getSystemWindowInsetTop(), 0, 0);
            ol.bottomMargin = Ui.dp(this, 24) + in.getSystemWindowInsetBottom();
            open.setLayoutParams(ol);
            return in;
        });
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Store.theme(this) != themeAtStart) { recreate(); return; }
        showRecents();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        // PDF drawings, and DXF (which phones often label as a generic file)
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/vnd.dxf", "image/x-dxf",
                "application/dxf", "application/x-dxf", "image/vnd.dwg", "image/x-dwg", "application/acad",
                "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK);
        } catch (Throwable t) {
            Toast.makeText(this, "No file picker on this device", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }
        view(uri);
    }

    private void view(Uri uri) {
        startActivity(new Intent(this, ViewerActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private void showRecents() {
        recentBox.removeAllViews();
        List<Store.Recent> list = Store.recents(this);
        if (list.isEmpty()) {
            TextView t = Ui.label(this, "Nothing opened yet.\n\nTo make Calque your PDF viewer, open a PDF from "
                    + "your files or a messenger, choose Calque and tap “Always”.", 15, Ui.DIM, false);
            t.setLineSpacing(Ui.dp(this, 3), 1);
            recentBox.addView(t);
            return;
        }
        TextView rh = Ui.label(this, "Recent", 14, Ui.DIM, true);
        rh.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 10));
        recentBox.addView(rh);
        for (Store.Recent r : list) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            row.setBackground(Ui.rippled(this, Ui.SURFACE | 0xFF000000, 20));
            row.setClickable(true);

            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_file);
            ic.setColorFilter(Ui.ACCENT);
            ic.setBackground(Ui.shape(Ui.CHIP, Ui.dp(this, 14)));
            int ip = Ui.dp(this, 10);
            ic.setPadding(ip, ip, ip, ip);
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(this, 14), 0, 0, 0);
            TextView nm = Ui.label(this, r.name, 15, Ui.TEXT, true);
            nm.setMaxLines(2);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(nm);
            texts.addView(Ui.label(this, DateUtils.getRelativeTimeSpanString(r.time, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString(), 13, Ui.DIM, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 10);
            recentBox.addView(row, lp);
            row.setOnClickListener((View v) -> view(Uri.parse(r.uri)));
            row.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Remove from recent?")
                        .setMessage(r.name + "\n\nOnly the entry in this list is removed. The PDF file itself is not deleted.")
                        .setPositiveButton("Remove", (d, w) -> {
                            Store.removeRecent(this, r.uri);
                            showRecents();
                        })
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
        }
    }
}
