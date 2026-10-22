package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a {@link LauncherTheme} palette into the drawables the chrome uses: the fallback
 * background, tile cards, frosted pills, chips and scrims. Prototypes are cached per theme id
 * and every caller gets a fresh copy: a Drawable instance must never be shared between views
 * (single callback → focused/pressed state would stop refreshing). All drawables are plain
 * gradients and rounded rectangles (no blur, no shaders).
 */
final class ThemeKit {

    private static final Map<String, Drawable> FALLBACKS = new HashMap<>();
    private static final Map<String, Drawable> CARDS = new HashMap<>();
    private static final Map<String, Drawable> PILL_BGS = new HashMap<>();
    private static final Map<String, Drawable> SPHERE_BGS = new HashMap<>();
    private static final Map<String, ColorStateList> PILL_TEXTS = new HashMap<>();
    private static final Map<String, Drawable> CHIP_SELECTED = new HashMap<>();
    private static final Map<String, Drawable> SCRIM_TOPS = new HashMap<>();
    private static final Map<String, Drawable> SCRIM_BOTTOMS = new HashMap<>();

    private ThemeKit() {
    }

    /** Fallback background: three-stop gradient plus two radial accent spots. */
    static Drawable fallback(Context c, LauncherTheme t) {
        Drawable cached = FALLBACKS.get(t.id);
        if (cached != null) return copy(cached, c);
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{t.bgTop, t.bgMid, t.bgBottom});
        LayerDrawable layer = new LayerDrawable(new Drawable[]{
                base,
                radial(t.accent, 0x4D, 0.82f, 0.10f, 420 * d),
                radial(t.accent2, 0x33, 0.08f, 0.96f, 520 * d)});
        FALLBACKS.put(t.id, layer);
        return copy(layer, c);
    }

    /** Tile surface: themed card colour, lightening while pressed. */
    static Drawable cardBackground(Context c, LauncherTheme t) {
        Drawable cached = CARDS.get(t.id);
        if (cached != null) return copy(cached, c);
        int radius = c.getResources().getDimensionPixelSize(R.dimen.tile_radius);
        StateListDrawable sel = new StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_pressed}, solid(t.cardPressed, radius));
        sel.addState(new int[]{}, solid(t.card, radius));
        CARDS.put(t.id, sel);
        return copy(sel, c);
    }

    /** Frosted pill: theme fill and stroke normally, theme focus fill when focused. */
    static Drawable pillBackground(Context c, LauncherTheme t) {
        Drawable cached = PILL_BGS.get(t.id);
        if (cached != null) return copy(cached, c);
        int radius = c.getResources().getDimensionPixelSize(R.dimen.pill_radius);
        StateListDrawable sel = new StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_focused}, solid(t.pillFocused, radius));
        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{t.pillFillTop, t.pillFillBottom});
        normal.setCornerRadius(radius);
        normal.setStroke(Math.max(1, (int) c.getResources().getDisplayMetrics().density), t.pillStroke);
        sel.addState(new int[]{}, normal);
        PILL_BGS.put(t.id, sel);
        return copy(sel, c);
    }

    static ColorStateList pillTextColors(LauncherTheme t) {
        ColorStateList cached = PILL_TEXTS.get(t.id);
        if (cached != null) return cached;
        ColorStateList csl = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{t.pillTextFocused, t.pillText});
        PILL_TEXTS.put(t.id, csl);
        return csl;
    }

    /** Applies the whole pill look (background, text and icon tint) to a button, chip or icon sphere. */
    static void applyPill(View v, LauncherTheme t) {
        if (v == null) return;
        v.setBackground(pillBackground(v.getContext(), t));
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextColor(pillTextColors(t));
            tv.setCompoundDrawableTintList(pillTextColors(t));
        } else if (v instanceof ImageView) {
            ImageView iv = (ImageView) v;
            iv.setImageTintList(pillTextColors(t));
        }
    }

    /** Selected chip: translucent fill with a 1.5dp accent rim. */
    static Drawable chipSelected(Context c, LauncherTheme t) {
        Drawable cached = CHIP_SELECTED.get(t.id);
        if (cached != null) return copy(cached, c);
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.isLight() ? 0x140B1220 : 0x1FFFFFFF);
        g.setStroke(Math.max(1, (int) (1.5f * d)), t.accent);
        g.setCornerRadius(c.getResources().getDimensionPixelSize(R.dimen.pill_radius));
        CHIP_SELECTED.put(t.id, g);
        return copy(g, c);
    }

    static Drawable scrimTop(Context c, LauncherTheme t) {
        Drawable cached = SCRIM_TOPS.get(t.id);
        if (cached != null) return copy(cached, c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{t.scrimTop, 0x00000000});
        SCRIM_TOPS.put(t.id, g);
        return copy(g, c);
    }

    static Drawable scrimBottom(Context c, LauncherTheme t) {
        Drawable cached = SCRIM_BOTTOMS.get(t.id);
        if (cached != null) return copy(cached, c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, t.scrimMid, t.scrimBottom});
        SCRIM_BOTTOMS.put(t.id, g);
        return copy(g, c);
    }

    
    /** Circular 44dp sphere background for TV header controls: theme fill/stroke normally, focus ring when focused. */
    static Drawable sphereBackground(Context c, LauncherTheme t) {
        Drawable cached = SPHERE_BGS.get(t.id);
        if (cached != null) return copy(cached, c);
        float d = c.getResources().getDisplayMetrics().density;
        StateListDrawable sel = new StateListDrawable();

        // Focused: frosted fill with 2dp crisp white focus ring
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        focused.setColor(t.pillFocused);
        focused.setStroke(Math.max(2, (int) (2f * d)), 0xFFFFFFFF);
        sel.addState(new int[]{android.R.attr.state_focused}, focused);

        // Pressed: illuminated
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(0x55FFFFFF);
        pressed.setStroke(Math.max(1, (int) (1.5f * d)), 0xFFFFFFFF);
        sel.addState(new int[]{android.R.attr.state_pressed}, pressed);

        // Normal: frosted fill and subtle rim
        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{t.pillFillTop, t.pillFillBottom});
        normal.setShape(GradientDrawable.OVAL);
        normal.setStroke(Math.max(1, (int) d), t.pillStroke);
        sel.addState(new int[]{}, normal);

        SPHERE_BGS.put(t.id, sel);
        return copy(sel, c);
    }

    /** Applies the sphere background to the container and tints any child ImageView with the theme colors. */
    static void applySphere(View container, LauncherTheme t) {
        if (container == null) return;
        container.setBackground(sphereBackground(container.getContext(), t));
        if (container instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) container;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof ImageView) {
                    ((ImageView) child).setImageTintList(pillTextColors(t));
                }
            }
        } else if (container instanceof ImageView) {
            ((ImageView) container).setImageTintList(pillTextColors(t));
        }
    }

    /** Rounded surface used behind the preview's dock mock. */
    static Drawable dockBand(Context c, LauncherTheme t) {
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.dockBand);
        g.setStroke(Math.max(1, (int) d), t.dockEdge);
        g.setCornerRadius(26 * d);
        return g;
    }

    /** Fresh instance of a cached prototype; a Drawable must not be shared between views. */
    private static Drawable copy(Drawable cached, Context c) {
        Drawable d = cached.getConstantState().newDrawable(c.getResources());
        d.mutate();
        return d;
    }

    private static GradientDrawable radial(int color, int alpha, float cx, float cy, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(radius);
        g.setColors(new int[]{(alpha << 24) | (color & 0x00FFFFFF), color & 0x00FFFFFF});
        return g;
    }

    private static GradientDrawable solid(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }
}
