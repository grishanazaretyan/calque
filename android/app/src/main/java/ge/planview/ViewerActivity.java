package ge.planview;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class ViewerActivity extends Activity implements PdfCore.Listener, PdfView.Listener {

    private static final double[] PRESETS = {1, 5, 10, 20, 25, 50, 75, 100, 200, 250, 500, 1000};

    private PdfCore core;
    private PdfView view;
    private LinearLayout topBar, bottomBar, nav, card, markCard;
    private final TextView[] toolBtns = new TextView[5];
    private final View[] colorDots = new View[5];
    private final View[] thickBtns = new View[3];
    private static final int[] COLORS = {0xFFE53935, 0xFF1E6FE0, 0xFF1E9E4A, 0xFFFF9800, 0xFF111111};
    private TextView title, subtitle, pageLabel, big, small, scaleBtn, segLength, segArea;
    private ImageButton rulerBtn, moreBtn, snapBtn, searchBtn;
    private LinearLayout names, searchBox;
    private android.widget.EditText searchField;
    private TextView searchCount;
    private float[] hitList;
    private int hitAt;
    private ProgressBar spinner, busy;

    private Uri uri;
    private String fileName = "", docKey = "";
    private float[] pw, ph;
    private String[] titles;
    private int pageCount, pageIndex;
    private boolean gotContent, chromeShown = true, hairline;
    private int insetTop, insetBottom, themeAtStart;

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        Ui.edgeToEdge(this);
        themeAtStart = Ui.themePref;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        uri = getIntent() != null ? getIntent().getData() : null;
        if (uri == null) { finish(); return; }
        if ((getIntent().getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) { }
        }
        String[] d = Store.describe(this, uri);
        fileName = d[0];
        docKey = d[0] + "|" + d[1];
        title.setText(fileName);
        if (Store.canReopenLater(this, uri)) Store.addRecent(this, uri, fileName);

        core = new PdfCore(this, this);
        view.attach(core, this);
        hairline = Store.hairline(this);
        if (hairline) core.setHairline(true, hairPx());
        core.open(uri);
    }

    // ---------- layout ----------

    private FrameLayout root;
    private EditText inline;          // note being typed directly on the drawing
    private Markup.Item inlineItem;   // the note it edits, or null for a new one
    private int inlinePage;
    private float inlineX, inlineY;

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        view = new PdfView(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));

        int ib = Ui.dp(this, 44);

        // floating top bar: back, file name + current sheet, menu
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        Ui.floating(topBar, 28);
        ImageButton back = Ui.icon(this, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(ib, ib));
        names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        title = Ui.label(this, "", 15, Ui.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        names.addView(title);
        subtitle = Ui.label(this, "Opening…", 12, Ui.DIM, false);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(subtitle);
        topBar.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        busy = new ProgressBar(this);
        busy.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        bl.rightMargin = Ui.dp(this, 6);
        topBar.addView(busy, bl);
        buildSearch(ib);
        searchBtn = Ui.icon(this, R.drawable.ic_search, "Search text");
        searchBtn.setOnClickListener(v -> openSearch());
        topBar.addView(searchBtn, new LinearLayout.LayoutParams(ib, ib));
        moreBtn = Ui.icon(this, R.drawable.ic_more, "View options");
        moreBtn.setOnClickListener(v -> showViewMenu());
        topBar.addView(moreBtn, new LinearLayout.LayoutParams(ib, ib));
        root.addView(topBar, new FrameLayout.LayoutParams(-1, Ui.dp(this, 56), Gravity.TOP));

        // floating bottom bar: sheets and the ruler
        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        Ui.floating(bottomBar, 30);
        nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setVisibility(View.GONE);
        ImageButton prev = Ui.icon(this, R.drawable.ic_prev, "Previous sheet");
        prev.setOnClickListener(v -> view.goToPage(pageIndex - 1));
        nav.addView(prev, new LinearLayout.LayoutParams(ib, ib));
        LinearLayout sheets = new LinearLayout(this);
        sheets.setGravity(Gravity.CENTER_VERTICAL);
        sheets.setBackground(Ui.rippled(this, Ui.CHIP, 22));
        sheets.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 16), 0);
        sheets.setClickable(true);
        sheets.setContentDescription("Sheets");
        ImageView li = new ImageView(this);
        li.setImageResource(R.drawable.ic_list);
        li.setColorFilter(Ui.TEXT);
        sheets.addView(li, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        pageLabel = Ui.label(this, "", 15, Ui.TEXT, true);
        pageLabel.setPadding(Ui.dp(this, 10), 0, 0, 0);
        sheets.addView(pageLabel);
        sheets.setOnClickListener(v -> showSheets());
        nav.addView(sheets, new LinearLayout.LayoutParams(-2, ib));
        ImageButton next = Ui.icon(this, R.drawable.ic_next, "Next sheet");
        next.setOnClickListener(v -> view.goToPage(pageIndex + 1));
        nav.addView(next, new LinearLayout.LayoutParams(ib, ib));
        View divider = new View(this);
        divider.setBackgroundColor(Ui.OUTLINE | 0x30000000);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 24));
        dl.leftMargin = dl.rightMargin = Ui.dp(this, 6);
        nav.addView(divider, dl);
        bottomBar.addView(nav);
        rulerBtn = Ui.icon(this, R.drawable.ic_ruler, "Measure");
        rulerBtn.setOnClickListener(v -> setMeasuring(true));
        bottomBar.addView(rulerBtn, new LinearLayout.LayoutParams(Ui.dp(this, 52), ib));
        ImageButton penBtn = Ui.icon(this, R.drawable.ic_pen, "Markup");
        penBtn.setOnClickListener(v -> setMarking(true));
        bottomBar.addView(penBtn, new LinearLayout.LayoutParams(Ui.dp(this, 52), ib));
        root.addView(bottomBar, new FrameLayout.LayoutParams(-2, Ui.dp(this, 60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        buildMarkCard();
        root.addView(markCard, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        markCard.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> updatePads());

        buildCard();
        root.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> updatePads());

        root.setOnApplyWindowInsetsListener((v, in) -> {
            insetTop = in.getSystemWindowInsetTop();
            insetBottom = in.getSystemWindowInsetBottom();
            int side = Ui.dp(this, 12);
            int l = side + in.getSystemWindowInsetLeft(), r = side + in.getSystemWindowInsetRight();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
            lp.setMargins(l, insetTop + Ui.dp(this, 8), r, 0);
            topBar.setLayoutParams(lp);
            lp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            lp.setMargins(l, 0, r, insetBottom + Ui.dp(this, 14));
            bottomBar.setLayoutParams(lp);
            lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            lp.setMargins(l, 0, r, insetBottom + Ui.dp(this, 12));
            card.setLayoutParams(lp);
            lp = (FrameLayout.LayoutParams) markCard.getLayoutParams();
            lp.setMargins(l, 0, r, insetBottom + Ui.dp(this, 12));
            markCard.setLayoutParams(lp);
            updatePads();
            return in;
        });
        setContentView(root);
    }

    private void buildCard() {
        int p = Ui.dp(this, 16);
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(p, Ui.dp(this, 14), p, p);
        card.setVisibility(View.GONE);
        card.setClickable(true);
        Ui.floating(card, 28);

        LinearLayout rowA = new LinearLayout(this);
        rowA.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        big = Ui.label(this, "", 28, Ui.TEXT, true);
        big.setSingleLine(true);
        texts.addView(big);
        small = Ui.label(this, "", 13, Ui.DIM, false);
        small.setLines(2);
        texts.addView(small);
        rowA.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        scaleBtn = Ui.button(this, "1:100", 0, Ui.ACCENT);
        GradientDrawable ring = Ui.shape(0, Ui.dp(this, 22));
        ring.setStroke(Ui.dp(this, 1.5f), Ui.ACCENT);
        scaleBtn.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.RIPPLE), ring, Ui.shape(0xFFFFFFFF, Ui.dp(this, 22))));
        scaleBtn.setOnClickListener(v -> chooseScale());
        rowA.addView(scaleBtn, new LinearLayout.LayoutParams(-2, -2));
        ImageButton close = Ui.icon(this, R.drawable.ic_close, "Stop measuring");
        close.setOnClickListener(v -> setMeasuring(false));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        cl.leftMargin = Ui.dp(this, 4);
        cl.rightMargin = -Ui.dp(this, 8);
        rowA.addView(close, cl);
        card.addView(rowA);

        LinearLayout rowB = new LinearLayout(this);
        rowB.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(Ui.shape(Ui.CHIP, Ui.dp(this, 22)));
        int sp = Ui.dp(this, 3);
        seg.setPadding(sp, sp, sp, sp);
        segLength = Ui.button(this, "Length", 0, Ui.TEXT);
        segArea = Ui.button(this, "Area", 0, Ui.TEXT);
        segLength.setMinHeight(Ui.dp(this, 38));
        segArea.setMinHeight(Ui.dp(this, 38));
        segLength.setOnClickListener(v -> { view.setMode(PdfView.MODE_DISTANCE); updateMeasure(); });
        segArea.setOnClickListener(v -> { view.setMode(PdfView.MODE_AREA); updateMeasure(); });
        seg.addView(segLength);
        seg.addView(segArea);
        rowB.addView(seg, new LinearLayout.LayoutParams(-2, -2));
        rowB.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        snapBtn = Ui.icon(this, R.drawable.ic_magnet, "Snap to lines");
        snapBtn.setOnClickListener(v -> {
            boolean on = !view.isSnap();
            Store.setSnap(this, on);
            view.setSnap(on);
            Toast.makeText(this, on ? "Snap to lines on" : "Snap off", Toast.LENGTH_SHORT).show();
            updateMeasure();
        });
        rowB.addView(snapBtn, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        TextView undo = Ui.button(this, "Undo", 0, Ui.TEXT);
        undo.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
        undo.setOnClickListener(v -> { view.undoPoint(); updateMeasure(); });
        rowB.addView(undo);
        TextView clear = Ui.button(this, "Clear", 0, Ui.TEXT);
        clear.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
        clear.setOnClickListener(v -> { view.clearPoints(); updateMeasure(); });
        rowB.addView(clear);
        LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(-1, -2);
        rb.topMargin = Ui.dp(this, 10);
        card.addView(rowB, rb);

        TextView add = Ui.button(this, "Add point", Ui.ACCENT, Ui.ON_ACCENT);
        add.setTextSize(16);
        add.setMinHeight(Ui.dp(this, 52));
        add.setBackground(Ui.rippled(this, Ui.ACCENT, 26));
        add.setOnClickListener(v -> { if (pageCount > 0) { view.addPoint(); updateMeasure(); } });
        TextView finish = Ui.button(this, "Finish", Ui.CHIP, Ui.TEXT);
        finish.setTextSize(16);
        finish.setMinHeight(Ui.dp(this, 52));
        finish.setBackground(Ui.rippled(this, Ui.CHIP, 26));
        finish.setOnClickListener(v -> {
            if (!view.finishMeasure()) {
                Toast.makeText(this, view.getMode() == PdfView.MODE_AREA ? "An area needs at least 3 corners"
                        : "A line needs at least 2 points", Toast.LENGTH_SHORT).show();
            }
            updateMeasure();
        });
        LinearLayout rowC = new LinearLayout(this);
        rowC.addView(finish, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams adl = new LinearLayout.LayoutParams(0, -2, 1.6f);
        adl.leftMargin = Ui.dp(this, 8);
        rowC.addView(add, adl);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = Ui.dp(this, 10);
        card.addView(rowC, al);
    }

    private void updatePads() {
        int top = insetTop + Ui.dp(this, 8 + 56 + 4);
        View shown = view.isMeasuring() ? card : view.isMarking() ? markCard : null;
        int bottom = shown != null && shown.getHeight() > 0
                ? shown.getHeight() + insetBottom + Ui.dp(this, 12 + 4)
                : insetBottom + Ui.dp(this, 14 + 60 + 4);
        view.setPads(top, bottom);
    }

    private void fade(View v, boolean show) {
        v.animate().cancel();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1).setDuration(150).withEndAction(null).start();
        } else {
            v.animate().alpha(0).setDuration(150).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    private void showChrome(boolean show) {
        chromeShown = show;
        fade(topBar, show);
        fade(bottomBar, show && !view.isMeasuring() && !view.isMarking());
    }

    private void showViewMenu() {
        PopupMenu m = new PopupMenu(this, moreBtn, Gravity.END);
        Menu menu = m.getMenu();
        if (pageCount > 1) {
            menu.add(1, 1, 0, "Continuous scroll").setCheckable(true).setChecked(view.isContinuous());
            menu.add(1, 2, 1, "One sheet at a time").setCheckable(true).setChecked(!view.isContinuous());
            menu.setGroupCheckable(1, true, true);
        }
        menu.add(2, 4, 2, "Hairlines").setCheckable(true).setChecked(hairline);
        menu.add(3, 5, 3, "Open in…");
        menu.add(3, 6, 4, "Share");
        menu.add(3, 7, 5, "Save to Files");
        menu.add(3, 8, 6, "Print");
        menu.add(3, 9, 7, "Export with markup…");
        menu.add(4, 3, 8, "Settings");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() >= 5 && item.getItemId() <= 8) { sendSrc = null; sendName = null; }
            if (item.getItemId() == 5) { sendFile(false); return true; }
            if (item.getItemId() == 6) { sendFile(true); return true; }
            if (item.getItemId() == 7) { saveToFiles(); return true; }
            if (item.getItemId() == 8) { printFile(); return true; }
            if (item.getItemId() == 9) { exportMarked(); return true; }
            if (item.getItemId() == 4) {
                Store.setHairline(this, !hairline);
                applyHairline();
                return true;
            }
            if (item.getItemId() == 3) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            boolean on = item.getItemId() == 1;
            Store.setContinuous(this, on);
            view.setContinuous(on);
            updateMeasure();
            return true;
        });
        m.show();
    }

    // ---------- open in / share / save ----------

    private static final int REQ_SAVE = 41;

    /** What Open in / Share / Save / Print send: an export with markup, a converted DXF, or the file. */
    private Uri sendSrc;
    private String sendName;

    private Uri src() { return sendSrc != null ? sendSrc : exportUri != null ? exportUri : uri; }

    private String pdfName() {
        if (sendName != null) return sendName;
        String n = fileName == null || fileName.trim().isEmpty() ? "drawing" : fileName.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        String low = n.toLowerCase(java.util.Locale.ROOT);
        if (low.endsWith(".pdf")) return n;
        if (low.endsWith(".dxf") || low.endsWith(".dwg")) n = n.substring(0, n.length() - 4);
        return n + ".pdf";
    }

    /** Copies the open PDF, on a background thread, then runs done with the result (or null). */
    private void copyOut(java.util.function.Function<java.io.InputStream, Object> sink, java.util.function.Consumer<Object> done) {
        Toast busy = Toast.makeText(this, "Preparing file…", Toast.LENGTH_SHORT);
        busy.show();
        new Thread(() -> {
            Object result = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(src())) {
                if (in != null) result = sink.apply(in);
            } catch (Exception ignored) { }
            Object r = result;
            runOnUiThread(() -> {
                busy.cancel();
                if (isFinishing()) return;
                if (r == null) Toast.makeText(this, "Could not read this file", Toast.LENGTH_LONG).show();
                else done.accept(r);
            });
        }, "calque-copy").start();
    }

    private static boolean pipe(java.io.InputStream in, java.io.OutputStream out) {
        try (java.io.OutputStream o = out) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** share = send to another app (messengers, mail…); otherwise open in another PDF app. */
    private void sendFile(boolean share) {
        java.io.File dir = ShareProvider.dir(this);
        java.io.File[] old = dir.listFiles();
        if (old != null) for (java.io.File f : old) f.delete();
        java.io.File out = new java.io.File(dir, pdfName());
        copyOut(in -> {
            try { return pipe(in, new java.io.FileOutputStream(out)) ? out : null; }
            catch (Exception e) { return null; }
        }, r -> {
            Uri u = ShareProvider.uriFor(out);
            Intent i;
            if (share) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType("application/pdf");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.putExtra(Intent.EXTRA_SUBJECT, out.getName());
                i.setClipData(android.content.ClipData.newRawUri(out.getName(), u));
            } else {
                i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(u, "application/pdf");
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, share ? "Share " + out.getName() : "Open in");
            if (!share) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                    new android.content.ComponentName[]{new android.content.ComponentName(this, ViewerActivity.class)});
            try { startActivity(chooser); }
            catch (Exception e) { Toast.makeText(this, share ? "No app to share with" : "No other app can open PDFs", Toast.LENGTH_LONG).show(); }
        });
    }

    /** Lets the user pick a folder (Downloads, Drive…) and saves a copy of the PDF there. */
    private void saveToFiles() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, pdfName());
        try { startActivityForResult(i, REQ_SAVE); }
        catch (Exception e) { Toast.makeText(this, "No file manager on this device", Toast.LENGTH_LONG).show(); }
    }

    /** Hands the PDF to Android printing (printers, "Save as PDF"). */
    private void printFile() {
        android.print.PrintManager pm = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) { Toast.makeText(this, "Printing is not available on this device", Toast.LENGTH_LONG).show(); return; }
        String name = pdfName();
        pm.print(name, new android.print.PrintDocumentAdapter() {
            @Override
            public void onLayout(android.print.PrintAttributes oldA, android.print.PrintAttributes newA,
                                 android.os.CancellationSignal cancel, LayoutResultCallback cb, android.os.Bundle extras) {
                if (cancel.isCanceled()) { cb.onLayoutCancelled(); return; }
                cb.onLayoutFinished(new android.print.PrintDocumentInfo.Builder(name)
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(pageCount > 0 ? pageCount : android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                        .build(), true);
            }

            @Override
            public void onWrite(android.print.PageRange[] pages, android.os.ParcelFileDescriptor dest,
                                android.os.CancellationSignal cancel, WriteResultCallback cb) {
                new Thread(() -> {
                    boolean ok = false;
                    try (java.io.InputStream in = getContentResolver().openInputStream(src())) {
                        ok = in != null && pipe(in, new java.io.FileOutputStream(dest.getFileDescriptor()));
                    } catch (Exception ignored) { }
                    if (cancel.isCanceled()) cb.onWriteCancelled();
                    else if (ok) cb.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
                    else cb.onWriteFailed("Could not read this file");
                }, "calque-print").start();
            }
        }, null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri dest = data.getData();
        copyOut(in -> {
            try { return pipe(in, getContentResolver().openOutputStream(dest, "w")) ? dest : null; }
            catch (Exception e) { return null; }
        }, r -> Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show());
    }

    // ---------- export with markup ----------

    /** A copy of the PDF with pen, text and measurements drawn in, then Share / Save / Print / Open in. */
    private void exportMarked() {
        if (pageCount == 0) return;
        if (!view.hasMarks()) {
            Toast.makeText(this, "Nothing drawn or measured yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] actions = {"Share", "Save to Files", "Print", "Open in…"};
        new AlertDialog.Builder(this).setTitle("Export with markup")
                .setItems(actions, (d, which) -> {
                    java.util.ArrayList<Float> ops = new java.util.ArrayList<>();
                    java.util.ArrayList<String> texts = new java.util.ArrayList<>();
                    view.exportOps(ops, texts);
                    float[] o = new float[ops.size()];
                    for (int i = 0; i < o.length; i++) o[i] = ops.get(i);
                    String base = pdfNameOf(fileName);
                    String name = base.substring(0, base.length() - 4) + " (markup).pdf";
                    java.io.File dir = new java.io.File(getCacheDir(), "marked");
                    dir.mkdirs();
                    java.io.File[] old = dir.listFiles();
                    if (old != null) for (java.io.File f : old) f.delete();
                    java.io.File out = new java.io.File(dir, name);
                    Toast busy = Toast.makeText(this, "Preparing file…", Toast.LENGTH_SHORT);
                    busy.show();
                    core.exportMarked(exportUri != null ? exportUri : uri, o, texts.toArray(new String[0]), out, ok -> {
                        busy.cancel();
                        if (!ok || isFinishing()) {
                            if (!ok) Toast.makeText(this, "Could not export this file", Toast.LENGTH_LONG).show();
                            return;
                        }
                        sendSrc = Uri.fromFile(out);
                        sendName = name;
                        if (which == 0) sendFile(true);
                        else if (which == 1) saveToFiles();
                        else if (which == 2) printFile();
                        else sendFile(false);
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static String pdfNameOf(String fileName) {
        String n = fileName == null || fileName.trim().isEmpty() ? "drawing" : fileName.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        String low = n.toLowerCase(java.util.Locale.ROOT);
        if (low.endsWith(".pdf")) return n;
        if (low.endsWith(".dxf") || low.endsWith(".dwg")) n = n.substring(0, n.length() - 4);
        return n + ".pdf";
    }

    // ---------- search ----------

    private void buildSearch(int ib) {
        searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setVisibility(View.GONE);
        searchField = new android.widget.EditText(this);
        searchField.setHint("Search text");
        searchField.setSingleLine(true);
        searchField.setTextColor(Ui.TEXT);
        searchField.setHintTextColor(Ui.DIM);
        searchField.setTextSize(15);
        searchField.setBackground(null);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setOnEditorActionListener((v, id, ev) -> { runSearch(); return true; });
        searchBox.addView(searchField, new LinearLayout.LayoutParams(0, -2, 1));
        searchCount = Ui.label(this, "", 13, Ui.DIM, false);
        searchCount.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        searchBox.addView(searchCount);
        ImageButton prev = Ui.icon(this, R.drawable.ic_prev, "Previous match");
        prev.setOnClickListener(v -> stepHit(-1));
        searchBox.addView(prev, new LinearLayout.LayoutParams(ib, ib));
        ImageButton next = Ui.icon(this, R.drawable.ic_next, "Next match");
        next.setOnClickListener(v -> stepHit(1));
        searchBox.addView(next, new LinearLayout.LayoutParams(ib, ib));
        ImageButton close = Ui.icon(this, R.drawable.ic_close, "Close search");
        close.setOnClickListener(v -> closeSearch());
        searchBox.addView(close, new LinearLayout.LayoutParams(ib, ib));
        topBar.addView(searchBox, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void openSearch() {
        if (pageCount == 0) return;
        names.setVisibility(View.GONE);
        searchBtn.setVisibility(View.GONE);
        moreBtn.setVisibility(View.GONE);
        searchBox.setVisibility(View.VISIBLE);
        searchField.requestFocus();
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(searchField, 0);
    }

    private void closeSearch() {
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchBox.setVisibility(View.GONE);
        names.setVisibility(View.VISIBLE);
        searchBtn.setVisibility(View.VISIBLE);
        moreBtn.setVisibility(View.VISIBLE);
        hitList = null;
        searchCount.setText("");
        view.setHits(null);
    }

    private boolean searchOpen() { return searchBox != null && searchBox.getVisibility() == View.VISIBLE; }

    private void runSearch() {
        String q = searchField.getText().toString().trim();
        if (q.isEmpty()) return;
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchCount.setText("…");
        core.search(q, r -> {
            hitList = r;
            int count = r == null ? 0 : r.length / 5;
            view.setHits(r);
            if (count == 0) {
                searchCount.setText("0");
                Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show();
                return;
            }
            hitAt = 0;
            view.showHit(0);
            searchCount.setText("1/" + count);
        });
    }

    private void stepHit(int d) {
        if (hitList == null || hitList.length < 5) { runSearch(); return; }
        int count = hitList.length / 5;
        hitAt = ((hitAt + d) % count + count) % count;
        view.showHit(hitAt);
        searchCount.setText((hitAt + 1) + "/" + count);
    }

    // ---------- sheets ----------

    private String sheetName(int i) {
        return titles != null && titles[i] != null ? titles[i] : "Sheet " + (i + 1);
    }

    @Override
    public void onPageChanged(int page) {
        if (pageCount == 0) return;
        pageIndex = page;
        pageLabel.setText((page + 1) + " / " + pageCount);
        String name = titles[page];
        if (pageCount > 1) subtitle.setText(name != null ? (page + 1) + "  ·  " + name : "Sheet " + (page + 1) + " of " + pageCount);
        else subtitle.setText(name != null ? name : Ui.paper(pw[page], ph[page]));
        Store.setLastPage(this, docKey, page);
        if (view.isMeasuring()) updateMeasure();
    }

    /** The sheet list: number, title and paper size; tap to jump. */
    private void showSheets() {
        if (pageCount == 0) return;
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float rad = Ui.dp(this, 28);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SURFACE | 0xFF000000);
        bg.setCornerRadii(new float[]{rad, rad, rad, rad, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(0, Ui.dp(this, 10), 0, insetBottom);

        View handle = new View(this);
        handle.setBackground(Ui.shape(Ui.DIM & 0x66FFFFFF, Ui.dp(this, 2)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(handle, hl);

        TextView head = Ui.label(this, "Sheets", 20, Ui.TEXT, true);
        head.setPadding(Ui.dp(this, 24), Ui.dp(this, 14), Ui.dp(this, 24), Ui.dp(this, 10));
        box.addView(head);

        final ArrayList<Integer> shown = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) shown.add(i);
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int pos) { return shown.get(pos); }
            @Override public long getItemId(int pos) { return shown.get(pos); }
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                LinearLayout row = (LinearLayout) cv;
                if (row == null) {
                    row = new LinearLayout(ViewerActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(Ui.dp(ViewerActivity.this, 20), Ui.dp(ViewerActivity.this, 9),
                            Ui.dp(ViewerActivity.this, 20), Ui.dp(ViewerActivity.this, 9));
                    TextView num = Ui.label(ViewerActivity.this, "", 14, Ui.TEXT, true);
                    num.setGravity(Gravity.CENTER);
                    num.setMinWidth(Ui.dp(ViewerActivity.this, 40));
                    num.setPadding(Ui.dp(ViewerActivity.this, 6), 0, Ui.dp(ViewerActivity.this, 6), 0);
                    row.addView(num, new LinearLayout.LayoutParams(-2, Ui.dp(ViewerActivity.this, 40)));
                    LinearLayout t = new LinearLayout(ViewerActivity.this);
                    t.setOrientation(LinearLayout.VERTICAL);
                    t.setPadding(Ui.dp(ViewerActivity.this, 14), 0, 0, 0);
                    TextView a = Ui.label(ViewerActivity.this, "", 15, Ui.TEXT, false);
                    a.setMaxLines(2);
                    a.setEllipsize(TextUtils.TruncateAt.END);
                    t.addView(a);
                    t.addView(Ui.label(ViewerActivity.this, "", 12, Ui.DIM, false));
                    row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                }
                int i = shown.get(pos);
                boolean cur = i == pageIndex;
                TextView num = (TextView) row.getChildAt(0);
                num.setText(String.valueOf(i + 1));
                num.setTextColor(cur ? Ui.ON_ACCENT : Ui.TEXT);
                num.setBackground(Ui.shape(cur ? Ui.ACCENT : Ui.CHIP, Ui.dp(ViewerActivity.this, 14)));
                LinearLayout t = (LinearLayout) row.getChildAt(1);
                ((TextView) t.getChildAt(0)).setText(sheetName(i));
                ((TextView) t.getChildAt(1)).setText(Ui.paper(pw[i], ph[i]));
                return row;
            }
        };

        if (pageCount > 12) {
            EditText find = new EditText(this);
            find.setHint("Find a sheet");
            find.setSingleLine(true);
            find.setTextSize(15);
            find.setTextColor(Ui.TEXT);
            find.setHintTextColor(Ui.DIM);
            find.setBackground(Ui.shape(Ui.CHIP, Ui.dp(this, 22)));
            find.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
            fl.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
            box.addView(find, fl);
            find.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable e) {
                    String q = e.toString().trim().toLowerCase(Locale.ROOT);
                    shown.clear();
                    for (int i = 0; i < pageCount; i++) {
                        if (q.isEmpty() || sheetName(i).toLowerCase(Locale.ROOT).contains(q)
                                || String.valueOf(i + 1).equals(q)) shown.add(i);
                    }
                    adapter.notifyDataSetChanged();
                }
            });
        }

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setSelection(Math.max(0, pageIndex - 2));
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            view.goToPage(shown.get(pos));
        });
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int rows = Ui.dp(this, 58) * pageCount + Ui.dp(this, pageCount > 12 ? 130 : 78) + insetBottom;
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(rows, Math.round(screenH * 0.72f)));
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
    }

    // ---------- PdfCore.Listener ----------

    @Override
    public void onOpened(float[] w, float[] h, String[] sheetTitles) {
        if (w.length == 0) { onError("This file has no pages"); return; }
        pw = w;
        ph = h;
        titles = sheetTitles;
        pageCount = w.length;
        nav.setVisibility(pageCount > 1 ? View.VISIBLE : View.GONE);
        view.setItems(Markup.load(markupFile()));
        view.setSnap(Store.snap(this));
        try {
            java.io.File mf = measureFile();
            if (mf.exists()) view.importMeasures(new String(java.nio.file.Files.readAllBytes(mf.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
        view.setDocument(w, h, Store.continuous(this), Store.lastPage(this, docKey));
    }

    /** For DXF drawings: the converted PDF, used for Share, Save and Print. */
    private Uri exportUri;

    @Override
    public void onDxf(java.io.File pdf, double ratio) {
        exportUri = Uri.fromFile(pdf);
        // drawn to a known scale, so measuring is right without calibrating
        if (!Store.hasRatio(this, docKey)) Store.setRatio(this, docKey, 0, ratio);
    }

    @Override
    public void onNeedPassword(boolean wrong) {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(wrong ? "Wrong password, try again" : "This PDF is password-protected")
                .setView(wrap(in)).setCancelable(false)
                .setPositiveButton("Open", (d, w) -> core.authenticate(in.getText().toString()))
                .setNegativeButton("Cancel", (d, w) -> finish()).show();
    }

    @Override
    public void onBase(int page, Bitmap bm, int seq) {
        if (!gotContent) {
            gotContent = true;
            spinner.setVisibility(View.GONE);
        }
        view.setBase(page, bm, seq);
    }

    @Override
    public void onPatch(Bitmap bm, float scale, int left, int top, int gen) {
        view.setPatch(bm, scale, left, top, gen);
    }

    @Override
    public void onRendering(boolean on) {
        busy.setVisibility(on && gotContent ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void onError(String message) {
        spinner.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (pageCount == 0) {
            Store.removeRecent(this, uri.toString());
            finish();
        }
    }

    private java.io.File measureFile() {
        return new java.io.File(new java.io.File(getFilesDir(), "measure"), Integer.toHexString(docKey.hashCode()) + ".json");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pageCount == 0) return;
        try {
            java.io.File mf = measureFile();
            mf.getParentFile().mkdirs();
            java.nio.file.Files.write(mf.toPath(), view.exportMeasures().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    @Override
    public void onSnap(int page, SnapIndex index) {
        view.setSnapIndex(page, index);
    }

    /** Hairline width in screen pixels: thin, but clearly visible on any screen. */
    private float hairPx() {
        return Math.max(1.5f, getResources().getDisplayMetrics().density * 0.7f);
    }

    private void applyHairline() {
        boolean want = Store.hairline(this);
        if (want == hairline || core == null) return;
        hairline = want;
        core.setHairline(want, hairPx());
    }

    @Override
    public void onModeApplied() {
        view.rerender();
    }

    // ---------- markup ----------

    private java.io.File markupFile() {
        return new java.io.File(new java.io.File(getFilesDir(), "markup"), Integer.toHexString(docKey.hashCode()) + ".json");
    }

    private void buildMarkCard() {
        int p = Ui.dp(this, 14);
        markCard = new LinearLayout(this);
        markCard.setOrientation(LinearLayout.VERTICAL);
        markCard.setPadding(p, p, p, p);
        markCard.setVisibility(View.GONE);
        markCard.setClickable(true);
        Ui.floating(markCard, 28);

        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(Ui.shape(Ui.CHIP, Ui.dp(this, 22)));
        int sp = Ui.dp(this, 3);
        seg.setPadding(sp, sp, sp, sp);
        String[] names = {"Pen", "Arrow", "Box", "Text", "Erase"};
        for (int i = 0; i < names.length; i++) {
            final int t = i;
            TextView b = Ui.button(this, names[i], 0, Ui.TEXT);
            b.setTextSize(14);
            b.setPadding(0, 0, 0, 0);
            b.setMinHeight(Ui.dp(this, 38));
            b.setOnClickListener(v -> { commitInline(); view.setTool(t); updateMarkCard(); });
            toolBtns[i] = b;
            seg.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        markCard.addView(seg, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < COLORS.length; i++) {
            final int col = COLORS[i];
            View dot = new View(this);
            dot.setContentDescription("Colour " + (i + 1));
            dot.setOnClickListener(v -> {
                view.setMarkColor(col);
                if (inline != null) inline.setTextColor(col);
                if (inlineItem != null) inlineItem.color = col;
                view.setSelectedColor(col);
                updateMarkCard();
            });
            colorDots[i] = dot;
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
            dl.rightMargin = Ui.dp(this, 7);
            row.addView(dot, dl);
        }
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        for (int i = 0; i < 3; i++) {
            final int t = i;
            View tb = new ThickButton(i);
            tb.setContentDescription(new String[]{"Thin", "Medium", "Thick"}[i]);
            tb.setOnClickListener(v -> {
                view.setThickness(t);
                if (view.getTool() == PdfView.TOOL_TEXT) view.setSelectedSize(t);
                updateMarkCard();
            });
            thickBtns[i] = tb;
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 32));
            tl.leftMargin = Ui.dp(this, 4);
            row.addView(tb, tl);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = Ui.dp(this, 12);
        markCard.addView(row, rl);

        LinearLayout row3 = new LinearLayout(this);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = Ui.label(this, "One finger draws \u00B7 two fingers move\nText: drag to move, corner to resize", 12, Ui.DIM, false);
        row3.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        TextView undo = Ui.button(this, "Undo", 0, Ui.TEXT);
        undo.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        undo.setOnClickListener(v -> { commitInline(); view.undoMarkup(); });
        row3.addView(undo);
        TextView done = Ui.button(this, "Done", Ui.ACCENT, Ui.ON_ACCENT);
        done.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        done.setOnClickListener(v -> setMarking(false));
        LinearLayout.LayoutParams dn = new LinearLayout.LayoutParams(-2, -2);
        dn.leftMargin = Ui.dp(this, 4);
        row3.addView(done, dn);
        LinearLayout.LayoutParams r3 = new LinearLayout.LayoutParams(-1, -2);
        r3.topMargin = Ui.dp(this, 10);
        markCard.addView(row3, r3);
    }

    /** Thin / medium / thick choice: shows a line, or a letter of that size for the Text tool. */
    private final class ThickButton extends View {
        private final int index;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();

        ThickButton(int index) {
            super(ViewerActivity.this);
            this.index = index;
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            boolean on = view.getThickness() == index;
            float r = getHeight() / 2f, d = getResources().getDisplayMetrics().density;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(on ? Ui.ACCENT : Ui.CHIP);
            box.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(box, r, r, paint);
            paint.setColor(on ? Ui.ON_ACCENT : Ui.TEXT);
            if (view.getTool() == PdfView.TOOL_TEXT) {
                paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setTextSize(new float[]{10, 14, 19}[index] * d);
                c.drawText("A", getWidth() / 2f, getHeight() / 2f - (paint.descent() + paint.ascent()) / 2, paint);
            } else {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                paint.setStrokeWidth(PdfView.STROKE_DP[index] * d);
                c.drawLine(getWidth() * 0.28f, getHeight() / 2f, getWidth() * 0.72f, getHeight() / 2f, paint);
            }
        }
    }

    private void updateMarkCard() {
        for (int i = 0; i < toolBtns.length; i++) segment(toolBtns[i], view.getTool() == i);
        for (int i = 0; i < COLORS.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(COLORS[i]);
            boolean on = view.getMarkColor() == COLORS[i];
            g.setStroke(Ui.dp(this, on ? 3 : 1), on ? Ui.TEXT : Ui.OUTLINE);
            colorDots[i].setBackground(g);
        }
        boolean eraser = view.getTool() == PdfView.TOOL_ERASE;
        for (View tb : thickBtns) {
            tb.setAlpha(eraser ? 0.35f : 1f);
            tb.setEnabled(!eraser);
            tb.invalidate();
        }
    }

    private void setMarking(boolean on) {
        if (pageCount == 0) return;
        if (!on) commitInline();
        if (on && view.isMeasuring()) setMeasuring(false);
        view.setMarking(on);
        markCard.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            chromeShown = true;
            fade(topBar, true);
            bottomBar.animate().cancel();
            bottomBar.setVisibility(View.GONE);
            updateMarkCard();
        } else {
            bottomBar.setAlpha(1);
            bottomBar.setVisibility(View.VISIBLE);
        }
        updatePads();
    }

    @Override
    public void onMarkupChanged() {
        Markup.save(markupFile(), view.getItems());
    }

    // ---------- text typed in place ----------

    @Override
    public void onTextRequested(int page, float x, float y, float screenX, float screenY) {
        commitInline();
        inlineItem = null;
        inlinePage = page;
        inlineX = x;
        inlineY = y;
        float size = PdfView.TEXT_DP[view.getThickness()] * getResources().getDisplayMetrics().density;
        openInline("", view.getMarkColor(), size, screenX, screenY);
    }

    @Override
    public void onTextEdit(Markup.Item it) {
        commitInline();
        inlineItem = it;
        android.graphics.RectF r = view.textRect(it);
        view.setEditing(it);
        openInline(it.text, it.color, view.screenSize(it), r.left, r.top);
    }

    @Override
    public boolean onCanvasTouch() {
        boolean was = inline != null;
        commitInline();
        return was;
    }

    @Override
    public void onSelectionChanged(Markup.Item item) { }

    private void openInline(String text, int color, float sizePx, float sx, float sy) {
        float d = getResources().getDisplayMetrics().density;
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(text);
        e.setSelection(text.length());
        e.setTextColor(color);
        e.setHint("Type here");
        e.setHintTextColor((color & 0x00FFFFFF) | 0x80000000);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(14 * d, Math.min(56 * d, sizePx)));
        e.setIncludeFontPadding(false);
        int pad = Math.round(4 * d);
        e.setPadding(pad, pad, pad, pad);
        e.setMinWidth(Math.round(120 * d));
        e.setMaxWidth(Math.round(view.getWidth() * 0.9f));
        GradientDrawable bg = Ui.shape(0x1A1E6FE0, Math.round(4 * d));
        bg.setStroke(Math.round(1.5f * d), 0xFF1E6FE0, 5 * d, 4 * d);
        e.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = Math.max(0, Math.min(Math.round(sx) - pad, view.getWidth() - Math.round(140 * d)));
        lp.topMargin = Math.max(0, Math.round(sy) - pad);
        root.addView(e, lp);
        inline = e;
        e.requestFocus();
        e.post(() -> {
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /** Finishes the note being typed: adds it, saves the new wording, or deletes it if emptied. */
    private void commitInline() {
        EditText e = inline;
        if (e == null) return;
        inline = null;
        String t = e.getText().toString().trim();
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(e.getWindowToken(), 0);
        root.removeView(e);
        Markup.Item it = inlineItem;
        inlineItem = null;
        view.setEditing(null);
        if (it == null) {
            if (!t.isEmpty()) view.addText(inlinePage, inlineX, inlineY, t);
        } else if (t.isEmpty()) {
            view.removeItem(it);
        } else if (!t.equals(it.text)) {
            String old = it.text;
            it.text = t;
            view.commitTextEdit(it, old, it.width, it.color, it.pts[0], it.pts[1]);
        }
    }

    // ---------- measuring ----------

    private void setMeasuring(boolean on) {
        if (on && view.isMarking()) setMarking(false);
        view.setMeasuring(on);
        card.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            chromeShown = true;
            fade(topBar, true);
            bottomBar.animate().cancel();
            bottomBar.setVisibility(View.GONE);
        } else {
            bottomBar.setAlpha(1);
            bottomBar.setVisibility(View.VISIBLE);
        }
        updatePads();
        updateMeasure();
    }

    @Override
    public void onSingleTap() {
        if (view.isMeasuring()) return;
        showChrome(!chromeShown);
    }

    @Override
    public void onMeasureChanged() {
        updateMeasure();
    }

    @Override
    public double ratioOf(int page) {
        return Store.ratio(this, docKey, page);
    }

    private static String scaleLabel(double r) {
        if (r >= 1) {
            return Math.abs(r - Math.rint(r)) < r * 0.002 ? String.format(Locale.US, "1:%d", Math.round(r))
                    : String.format(Locale.US, "1:%.2f", r);
        }
        double k = 1 / r;
        return Math.abs(k - Math.rint(k)) < k * 0.002 ? String.format(Locale.US, "%d:1", Math.round(k))
                : String.format(Locale.US, "%.2f:1", k);
    }

    private void segment(TextView t, boolean on) { Ui.segment(t, on); }

    private void updateMeasure() {
        if (card.getVisibility() != View.VISIBLE || pageCount == 0) return;
        scaleBtn.setText(scaleLabel(ratioOf(view.measurePage())));
        snapBtn.setColorFilter(view.isSnap() ? Ui.ACCENT : Ui.DIM);
        boolean areaMode = view.getMode() == PdfView.MODE_AREA;
        segment(segLength, !areaMode);
        segment(segArea, areaMode);
        int n = view.pointCount(), kept = view.finishedCount();
        if (n == 0) {
            big.setText(areaMode ? "Area" : "Length");
            small.setText(kept > 0
                    ? kept + (kept == 1 ? " measurement" : " measurements") + " kept \u00B7 aim at the next start point and tap Add point"
                    : "Move the drawing until the cross is on the first point, then tap Add point");
            return;
        }
        if (!areaMode) {
            double placed = view.placedLengthMm(), live = view.liveLengthMm();
            if (n == 1) {
                big.setText(Units.len(live));
                small.setText("Move the cross to the next point, then Add point");
            } else {
                big.setText(Units.len(placed));
                small.setText("to the cross: +" + Units.len(live) + "  \u00B7  Finish ends this line");
            }
        } else {
            if (n < 2) {
                big.setText("Area");
                small.setText("Mark the corners one by one \u2014 at least 3");
            } else {
                double[] a = view.liveArea();
                big.setText(Units.area(a[0]));
                small.setText("perimeter " + Units.len(a[1]) + "  \u00B7  Finish closes the shape");
            }
        }
    }

    private void applyRatio(double r) {
        if (!(r > 0) || Double.isInfinite(r)) return;
        Store.setRatio(this, docKey, view.measurePage(), r);
        view.invalidate();
        updateMeasure();
    }

    private void chooseScale() {
        String[] items = new String[PRESETS.length + 2];
        items[0] = "Calibrate from a known dimension…";
        items[1] = "Other scale…";
        for (int i = 0; i < PRESETS.length; i++) items[i + 2] = scaleLabel(PRESETS[i]);
        new AlertDialog.Builder(this).setTitle("Drawing scale")
                .setItems(items, (d, which) -> {
                    if (which == 0) calibrate();
                    else if (which == 1) askCustomScale();
                    else applyRatio(PRESETS[which - 2]);
                }).show();
    }

    private void askCustomScale() {
        EditText in = numberInput();
        in.setHint("for 1:150 type 150");
        showWithKeyboard(in, new AlertDialog.Builder(this).setTitle("Scale 1 : ?").setView(wrap(in))
                .setPositiveButton("OK", (d, w) -> applyRatio(parse(in)))
                .setNegativeButton("Cancel", null).create());
    }

    private void calibrate() {
        final double seg = view.lastSegmentPt();
        if (seg <= 0) {
            new AlertDialog.Builder(this).setTitle("Calibrate")
                    .setMessage("First mark the two ends of a dimension whose real length you know "
                            + "(for example a dimension line that says 3600), then open this menu again.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        EditText in = numberInput();
        in.setHint("real length in " + Units.inputName());
        showWithKeyboard(in, new AlertDialog.Builder(this).setTitle("Real length of the last segment, " + Units.inputName())
                .setView(wrap(in))
                .setPositiveButton("Set scale", (d, w) -> {
                    double mm = Units.toMm(parse(in));
                    if (mm > 0) {
                        applyRatio(PdfView.ratioFor(seg, mm));
                        Toast.makeText(this, "Scale set to " + scaleLabel(ratioOf(view.measurePage())), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null).create());
    }

    private static double parse(EditText in) {
        try { return Double.parseDouble(in.getText().toString().trim().replace(',', '.')); }
        catch (NumberFormatException e) { return 0; }
    }

    private EditText numberInput() {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setSingleLine(true);
        return in;
    }

    private void showWithKeyboard(EditText in, AlertDialog d) {
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
        in.requestFocus();
    }

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        f.setPadding(p, Ui.dp(this, 8), p, 0);
        f.addView(v);
        return f;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Store.theme(this) != themeAtStart) { recreate(); return; }
        Units.unit = Store.unit(this);
        applyHairline();
        view.invalidate();
        updateMeasure();
    }

    @Override
    public void onBackPressed() {
        if (inline != null) commitInline();
        else if (searchOpen()) closeSearch();
        else if (view != null && view.isMarking()) setMarking(false);
        else if (view != null && view.isMeasuring()) setMeasuring(false);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (core != null) core.close();
        super.onDestroy();
    }
}
