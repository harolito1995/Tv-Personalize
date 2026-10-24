package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a {@link LauncherTheme} palette into the drawables the chrome uses: the fallback
 * background, tile cards, frosted pills, chips, text fields, dialogs and scrims. Prototypes are
 * cached per theme id and every caller gets a fresh copy: a Drawable instance must never be shared
 * between views (single callback -> focused/pressed state would stop refreshing). All drawables are
 * gradient surfaces and rounded rectangles; card outlines follow live view bounds.
 */
final class ThemeKit {

    private static final Map<String, Drawable> FALLBACKS = new HashMap<>();
    private static final Map<String, Drawable> CARDS = new HashMap<>();
    private static final Map<String, Drawable> PILL_BGS = new HashMap<>();
    private static final Map<String, Drawable> SPHERE_BGS = new HashMap<>();
    private static final Map<String, ColorStateList> PILL_TEXTS = new HashMap<>();
    private static final Map<String, ColorStateList> TOOL_LABEL_TEXTS = new HashMap<>();
    private static final Map<String, Drawable> CHIP_SELECTED = new HashMap<>();
    private static final Map<String, ColorStateList> CHIP_SELECTED_TEXTS = new HashMap<>();
    private static final Map<String, Drawable> TEXT_FIELD_BGS = new HashMap<>();
    private static final Map<String, Drawable> POPUP_ROW_BGS = new HashMap<>();
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
        return cardBackground(c, t, c.getResources().getDimensionPixelSize(R.dimen.tile_radius));
    }

    /** Tile surface for an explicit pixel corner radius (e.g. 14dp large tiles vs 10dp compact tiles). */
    static Drawable cardBackground(Context c, LauncherTheme t, int radiusPx) {
        String key = t.id + ":" + radiusPx;
        Drawable cached = CARDS.get(key);
        if (cached != null) return copy(cached, c);
        StateListDrawable sel = new StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_pressed}, solid(t.cardPressed, radiusPx));
        sel.addState(new int[]{}, solid(t.card, radiusPx));
        CARDS.put(key, sel);
        return copy(sel, c);
    }

    /**
     * Reusable focus outline; its path follows the live view bounds and supplied corner radius.
     */
    static Drawable cardFocusForeground(Context c, LauncherTheme t) {
        return cardFocusForeground(c, t, c.getResources().getDimensionPixelSize(R.dimen.tile_radius));
    }

    /**
     * Draws a focus border directly from the card's live bounds, so it hugs each measured size
     * instead of relying on an inset layer with separate rounded-rectangle geometry.
     */
    static Drawable cardFocusForeground(Context c, LauncherTheme t, int radiusPx) {
        return new AdaptiveCardFocusDrawable(
                t.cardFocusStroke, t.cardSelectedStroke, t.cardBorderIdle,
                radiusPx, c.getResources().getDisplayMetrics().density);
    }

    /** A bounds-aware outline; its stroke reaches the card's exact outer silhouette. */
    private static final class AdaptiveCardFocusDrawable extends Drawable {
        private int drawableAlpha = 255;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outline = new RectF();
        private final float cornerRadiusPx;
        private final float focusStrokePx;
        private final float focusKeylinePx;
        private final float quietStrokePx;
        private final int focusColor;
        private final int selectedColor;
        private final int idleColor;
        private boolean focused;
        private boolean selected;

        AdaptiveCardFocusDrawable(int focusColor, int selectedColor, int idleColor,
                                  int cornerRadiusPx, float density) {
            this.focusColor = focusColor;
            this.selectedColor = selectedColor;
            this.idleColor = idleColor;
            this.cornerRadiusPx = Math.max(0, cornerRadiusPx);
            this.focusStrokePx = Math.max(1f, 2f * density);
            this.focusKeylinePx = Math.max(focusStrokePx + 1f, 3.5f * density);
            this.quietStrokePx = Math.max(1f, density);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float stroke = focused ? focusKeylinePx : quietStrokePx;
            float half = stroke * 0.5f;
            outline.set(bounds.left + half, bounds.top + half,
                    bounds.right - half, bounds.bottom - half);
            if (outline.width() <= 0f || outline.height() <= 0f) return;

            float radius = Math.min(cornerRadiusPx - half,
                    Math.min(outline.width(), outline.height()) * 0.5f);
            radius = Math.max(0f, radius);
            paint.setStrokeWidth(stroke);
            int color = focused ? 0xD9000000 : selected ? selectedColor : idleColor;
            int alpha = ((color >>> 24) * drawableAlpha) / 255;
            paint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            canvas.drawRoundRect(outline, radius, radius, paint);
            if (focused) {
                float brightStroke = focusStrokePx;
                float brightHalf = brightStroke * 0.5f;
                paint.setStrokeWidth(brightStroke);
                int focusAlpha = ((focusColor >>> 24) * drawableAlpha) / 255;
                paint.setColor((focusColor & 0x00FFFFFF) | (focusAlpha << 24));
                canvas.drawRoundRect(outline, radius, radius, paint);
            }
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean wasFocused = focused;
            boolean wasSelected = selected;
            focused = false;
            selected = false;
            for (int value : state) {
                if (value == android.R.attr.state_focused) focused = true;
                if (value == android.R.attr.state_selected) selected = true;
            }
            if (wasFocused != focused || wasSelected != selected) {
                invalidateSelf();
                return true;
            }
            return false;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            drawableAlpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
    /** Applies both the themed card surface and the D-pad focus foreground to a card view. */
    static void applyCard(View card, LauncherTheme t) {
        if (card == null) return;
        Context c = card.getContext();
        int radiusPx = c.getResources().getDimensionPixelSize(R.dimen.tile_radius);
        applyCard(card, t, radiusPx);
    }

    /** Applies the themed card surface, clean focus ring and matching outline clip for a given radius. */
    static void applyCard(View card, LauncherTheme t, final int radiusPx) {
        if (card == null) return;
        Context c = card.getContext();
        card.setBackground(cardBackground(c, t, radiusPx));
        card.setForeground(cardFocusForeground(c, t, radiusPx));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        card.setClipToOutline(true);
    }

    /**
     * Frosted pill background with distinct states for focused, pressed, normal and disabled.
     */
    static Drawable pillBackground(Context c, LauncherTheme t) {
        Drawable cached = PILL_BGS.get(t.id);
        if (cached != null) return copy(cached, c);
        float d = c.getResources().getDisplayMetrics().density;
        int radius = c.getResources().getDimensionPixelSize(R.dimen.pill_radius);
        int strokeIdle = Math.max(1, Math.round(1f * d));
        int strokeFocus = Math.max(1, Math.round(1.25f * d));

        StateListDrawable sel = new StateListDrawable();

        // Disabled state
        GradientDrawable disabled = new GradientDrawable();
        disabled.setShape(GradientDrawable.RECTANGLE);
        disabled.setCornerRadius(radius);
        disabled.setColor((0x14 << 24) | (t.pillFillBottom & 0x00FFFFFF));
        disabled.setStroke(strokeIdle, (0x18 << 24) | (t.pillStroke & 0x00FFFFFF));
        sel.addState(new int[]{-android.R.attr.state_enabled}, disabled);

        // Focused state: matte theme surface with a quiet neutral rim
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.RECTANGLE);
        focused.setCornerRadius(radius);
        focused.setColor(t.controlFocusFill);
        focused.setStroke(strokeFocus, t.controlFocusStroke);
        sel.addState(new int[]{android.R.attr.state_focused}, focused);

        // Pressed state
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(radius);
        pressed.setColor(t.controlFocusFill);
        pressed.setStroke(strokeIdle, t.controlFocusStroke);
        sel.addState(new int[]{android.R.attr.state_pressed}, pressed);

        // Normal resting state
        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{t.pillFillTop, t.pillFillBottom});
        normal.setCornerRadius(radius);
        normal.setStroke(strokeIdle, t.pillStroke);
        sel.addState(new int[]{}, normal);

        PILL_BGS.put(t.id, sel);
        return copy(sel, c);
    }

    static ColorStateList pillTextColors(LauncherTheme t) {
        ColorStateList cached = PILL_TEXTS.get(t.id);
        if (cached != null) return cached;
        int disabledText = (0x55 << 24) | (t.textDim & 0x00FFFFFF);
        ColorStateList csl = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{disabledText, t.textPrimary, t.textPrimary, t.pillText});
        PILL_TEXTS.put(t.id, csl);
        return csl;
    }

    /** Tool labels sit outside their dark circles, so they use the same text palette as the header. */
    static ColorStateList toolLabelColors(LauncherTheme t) {
        ColorStateList cached = TOOL_LABEL_TEXTS.get(t.id);
        if (cached != null) return cached;
        ColorStateList csl = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{t.textPrimary, t.textSecondary});
        TOOL_LABEL_TEXTS.put(t.id, csl);
        return csl;
    }

    /** Theme accent when idle, theme primary text while focused for contrast on the focus surface. */
    static ColorStateList toolAccentIconColors(LauncherTheme t) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{t.textPrimary, t.accent});
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

    /**
     * Selected chip background: responds to D-pad focus with a clear high-contrast active state
     * instead of remaining visually frozen when focused.
     */
    static Drawable chipSelected(Context c, LauncherTheme t) {
        Drawable cached = CHIP_SELECTED.get(t.id);
        if (cached != null) return copy(cached, c);
        float d = c.getResources().getDisplayMetrics().density;
        int radius = c.getResources().getDimensionPixelSize(R.dimen.pill_radius);
        int strokeIdle = Math.max(1, Math.round(1.5f * d));
        int strokeFocused = Math.max(1, Math.round(1.25f * d));

        StateListDrawable sel = new StateListDrawable();

        // Selected + Focused by D-pad: calm surface shift and a single neutral rim
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.RECTANGLE);
        focused.setCornerRadius(radius);
        focused.setColor(t.controlFocusFill);
        focused.setStroke(strokeFocused, t.controlFocusStroke);
        sel.addState(new int[]{android.R.attr.state_focused}, focused);
        sel.addState(new int[]{android.R.attr.state_pressed}, focused);

        // Selected resting (unfocused): subtle accent-tinted surface with accent rim
        GradientDrawable resting = new GradientDrawable();
        resting.setShape(GradientDrawable.RECTANGLE);
        resting.setCornerRadius(radius);
        resting.setColor(t.chipSelectedFill);
        resting.setStroke(strokeIdle, t.chipSelectedStroke);
        sel.addState(new int[]{}, resting);

        CHIP_SELECTED.put(t.id, sel);
        return copy(sel, c);
    }

    /** Text and icon ColorStateList for a chip that is currently selected/active. */
    static ColorStateList chipSelectedTextColors(LauncherTheme t) {
        ColorStateList cached = CHIP_SELECTED_TEXTS.get(t.id);
        if (cached != null) return cached;
        int disabledText = (0x55 << 24) | (t.textDim & 0x00FFFFFF);
        ColorStateList csl = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{disabledText, t.textPrimary, t.textPrimary, t.chipSelectedText});
        CHIP_SELECTED_TEXTS.put(t.id, csl);
        return csl;
    }

    /**
     * Applies a complete 5-state selectable chip style (Normal, Selected-Idle, Focused,
     * Selected-Focused, Disabled) so active chips always show an unmistakable D-pad focus change.
     */
    static void applySelectableChip(TextView chip, LauncherTheme t, boolean selected) {
        if (chip == null) return;
        Context c = chip.getContext();
        chip.setBackground(selected ? chipSelected(c, t) : pillBackground(c, t));
        ColorStateList colors = selected ? chipSelectedTextColors(t) : pillTextColors(t);
        chip.setTextColor(colors);
        chip.setCompoundDrawableTintList(colors);
    }

    /**
     * Dedicated text-field surface (search inputs, location inputs). Unlike {@link #pillBackground},
     * focusing an input field NEVER inverts the background to white while leaving the text white:
     * it preserves a stable high-contrast input surface and highlights the border with a crisp 2dp
     * theme focus ring.
     */
    static Drawable textFieldBackground(Context c, LauncherTheme t) {
        Drawable cached = TEXT_FIELD_BGS.get(t.id);
        if (cached != null) return copy(cached, c);
        float d = c.getResources().getDisplayMetrics().density;
        int radius = c.getResources().getDimensionPixelSize(R.dimen.input_radius);
        int strokeIdle = Math.max(1, Math.round(1f * d));
        int strokeFocus = Math.max(1, Math.round(1.25f * d));

        StateListDrawable sel = new StateListDrawable();

        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.RECTANGLE);
        focused.setCornerRadius(radius);
        focused.setColor(t.surfaceInputFocused);
        focused.setStroke(strokeFocus, t.controlFocusStroke);
        sel.addState(new int[]{android.R.attr.state_focused}, focused);

        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(radius);
        normal.setColor(t.surfaceInput);
        normal.setStroke(strokeIdle, t.inputStrokeIdle);
        sel.addState(new int[]{}, normal);

        TEXT_FIELD_BGS.put(t.id, sel);
        return copy(sel, c);
    }

    /**
     * Styles an {@link EditText} for Android TV keyboard / D-pad entry with guaranteed contrast
     * in all four themes (Midnight Glass, Aurora Cinema, Arctic Light, Ember).
     */
    static void applyTextField(EditText input, LauncherTheme t) {
        if (input == null) return;
        Context c = input.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        input.setBackground(textFieldBackground(c, t));
        input.setTextColor(t.textPrimary);
        input.setHintTextColor(t.textDim);
        input.setHighlightColor((0x55 << 24) | (t.accent & 0x00FFFFFF));
        int padH = Math.round(16f * d);
        int padV = Math.round(10f * d);
        input.setPadding(padH, padV, padH, padV);
    }

    /** Rounded modal/dialog/popup panel surface matching the active theme and 22dp panel radius. */
    static Drawable dialogSurface(Context c, LauncherTheme t) {
        float d = c.getResources().getDisplayMetrics().density;
        int radius = c.getResources().getDimensionPixelSize(R.dimen.panel_radius);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setColor(t.surfaceDialog);
        g.setStroke(Math.max(1, Math.round(1f * d)), t.cardBorderIdle);
        return g;
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

    /** Circular sphere background for TV header controls: theme fill/stroke normally, focus ring when focused. */
    static Drawable sphereBackground(Context c, LauncherTheme t) {
        Drawable cached = SPHERE_BGS.get(t.id);
        if (cached != null) return copy(cached, c);
        float d = c.getResources().getDisplayMetrics().density;
        StateListDrawable sel = new StateListDrawable();

        // Focused: high-contrast fill with 2dp crisp theme focus ring
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        focused.setColor(t.controlFocusFill);
        focused.setStroke(Math.max(1, Math.round(1.25f * d)), t.controlFocusStroke);
        sel.addState(new int[]{android.R.attr.state_focused}, focused);

        // Pressed: illuminated
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(t.controlFocusFill);
        pressed.setStroke(Math.max(1, Math.round(1f * d)), t.controlFocusStroke);
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
        if (container instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) container;
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

    /** Non-focusable header capsule background (e.g. Home weather summary pill). */
    static Drawable weatherCapsuleBackground(Context c, LauncherTheme t) {
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{t.pillFillTop, t.pillFillBottom});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Math.round(14f * d));
        g.setStroke(Math.max(1, Math.round(1f * d)), t.pillStroke);
        return g;
    }

    /**
     * Applies the theme to a bottom-bar tool button (circle + icon + external label).
     * When {@code tintIcon} is false (e.g. multi-colour weather icon), the icon bitmap/vector
     * preserves its native colours while the circle and label still respond to D-pad focus.
     */
    static void applyToolButton(View circle, ImageView icon, TextView label,
                                LauncherTheme t, boolean tintIcon) {
        if (circle != null) {
            circle.setBackground(sphereBackground(circle.getContext(), t));
        }
        if (icon != null) {
            icon.setImageTintList(tintIcon ? pillTextColors(t) : null);
        }
        if (label != null) {
            label.setTextColor(toolLabelColors(t));
        }
    }

    /** Styles a popup container panel (HDMI inputs, Dock context menu) with the theme dialog surface. */
    static void applyPopupPanel(View panel, LauncherTheme t) {
        if (panel == null) return;
        panel.setBackground(dialogSurface(panel.getContext(), t));
    }

    /** Focusable row selector inside a popup menu: transparent at rest, high-contrast pill on focus. */
    static Drawable popupRowBackground(Context c, LauncherTheme t) {
        Drawable cached = POPUP_ROW_BGS.get(t.id);
        if (cached != null) return copy(cached, c);
        float d = c.getResources().getDisplayMetrics().density;
        int radius = Math.round(12f * d);
        int strokeFocus = Math.max(1, Math.round(1.25f * d));

        StateListDrawable sel = new StateListDrawable();

        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.RECTANGLE);
        focused.setCornerRadius(radius);
        focused.setColor(t.controlFocusFill);
        focused.setStroke(strokeFocus, t.controlFocusStroke);
        sel.addState(new int[]{android.R.attr.state_focused}, focused);
        sel.addState(new int[]{android.R.attr.state_pressed}, focused);

        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(radius);
        normal.setColor(0x00000000);
        sel.addState(new int[]{}, normal);

        POPUP_ROW_BGS.put(t.id, sel);
        return copy(sel, c);
    }

    /** Styles an individual row in a popup menu (`item_input.xml` or `item_menu.xml`). */
    static void applyPopupRow(View row, LauncherTheme t) {
        if (row == null) return;
        row.setBackground(popupRowBackground(row.getContext(), t));
        ColorStateList primaryColors = pillTextColors(t);
        View icon = row.findViewById(R.id.icon);
        if (icon instanceof ImageView) {
            ((ImageView) icon).setImageTintList(primaryColors);
        }
        View title = row.findViewById(R.id.title);
        if (title instanceof TextView) {
            ((TextView) title).setTextColor(primaryColors);
        }
        View status = row.findViewById(R.id.status);
        if (status instanceof TextView) {
            ColorStateList dimColors = new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_focused},
                            new int[]{android.R.attr.state_pressed},
                            new int[]{}
                    },
                    new int[]{t.textPrimary, t.textPrimary, t.textDim});
            ((TextView) status).setTextColor(dimColors);
        }
    }

    /** Rounded surface used behind the preview's dock mock. */
    static Drawable dockBand(Context c, LauncherTheme t) {
        float d = c.getResources().getDisplayMetrics().density;
        int radius = c.getResources().getDimensionPixelSize(R.dimen.panel_radius);
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.dockBand);
        g.setStroke(Math.max(1, (int) d), t.dockEdge);
        g.setCornerRadius(radius);
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
