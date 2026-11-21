package ge.planview;

import java.util.Locale;

/** Length and area formatting in the unit chosen in Settings. Values come in as millimetres. */
final class Units {
    static final int MM = 0, CM = 1, M = 2, FT = 3;
    static final String[] NAMES = {"mm", "cm", "m", "ft-in"};
    static int unit = MM;

    static String len(double mm) {
        switch (unit) {
            case CM: return group(mm / 10, 1) + " cm";
            case M: return String.format(Locale.US, "%.3f m", mm / 1000);
            case FT: {
                double in = mm / 25.4;
                long ft = (long) Math.floor(in / 12 + 1e-9);
                double r = in - ft * 12;
                if (r >= 11.95) { ft++; r = 0; }
                return ft > 0 ? String.format(Locale.US, "%d′ %.1f″", ft, r)
                        : String.format(Locale.US, "%.1f″", r);
            }
            default: return group(mm, 0) + " mm";
        }
    }

    static String area(double m2) {
        return unit == FT ? String.format(Locale.US, "%.2f ft²", m2 * 10.7639104)
                : String.format(Locale.US, "%.2f m²", m2);
    }

    /** Name of the unit a typed length is in. */
    static String inputName() { return unit == FT ? "inches" : NAMES[unit]; }

    static double toMm(double v) {
        switch (unit) {
            case CM: return v * 10;
            case M: return v * 1000;
            case FT: return v * 25.4;
            default: return v;
        }
    }

    private static String group(double v, int decimals) {
        return String.format(Locale.US, "%,." + decimals + "f", v).replace(',', ' ');
    }
}
