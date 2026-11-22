package com.harolhome.tv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
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
        float d = c.getResources().getDisplayMetrics().density;
        StateListDrawable sel = new StateListDrawable();
        if (t.isLiquidGlass()) {
            sel.addState(new int[]{android.R.attr.state_pressed},
                    glassRect(radiusPx, d, 0xE62E3F57, 0xCC1E2B3D, 0x48, 0, 0, 0, 0f));
            sel.addState(new int[]{},
                    glassRect(radiusPx, d, 0xCC223042, 0xB3151F2E, 0x36, 0, 0, 0, 0f));
        } else {
            sel.addState(new int[]{android.R.attr.state_pressed}, solid(t.cardPressed, radiusPx));
            sel.addState(new int[]{}, solid(t.card, radiusPx));
        }
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
                radiusPx, c.getResources().getDisplayMetrics().density, t.isLiquidGlass());
    }

    /** A bounds-aware outline; its stroke reaches the card's exact outer silhouette. */
    private static final class AdaptiveCardFocusDrawable extends Drawable {
        private int drawableAlpha = 255;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outline = new RectF();
        private final RectF specRect = new RectF();
        private final float density;
        private final float cornerRadiusPx;
        private final float focusStrokePx;
        private final float focusKeylinePx;
        private final float quietStrokePx;
        private final int focusColor;
        private final int selectedColor;
        private final int idleColor;
        private final boolean liquidGlass;
        private boolean focused;
        private boolean selected;

        AdaptiveCardFocusDrawable(int focusColor, int selectedColor, int idleColor,
                                  int cornerRadiusPx, float density, boolean liquidGlass) {
            this.focusColor = focusColor;
            this.selectedColor = selectedColor;
            this.idleColor = idleColor;
            this.density = density;
            this.liquidGlass = liquidGlass;
            this.cornerRadiusPx = Math.max(0, cornerRadiusPx);
            this.focusStrokePx = Math.max(1f, 2f * density);
            this.focusKeylinePx = Math.max(focusStrokePx + 1f, 3.5f * density);
            this.quietStrokePx = Math.max(1f, density);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            if (!focused && !selected) return;
            Rect bounds = getBounds();
            float stroke = focused ? focusKeylinePx : quietStrokePx;
            float half = stroke * 0.5f;
            outline.set(bounds.left + half, bounds.top + half,
                    bounds.right - half, bounds.bottom - half);
            if (outline.width() <= 0f || outline.height() <= 0f) return;

            float radius = Math.min(cornerRadiusPx - half,
                    Math.min(outline.width(), outline.height()) * 0.5f);
            radius = Math.max(0f, radius);
            if (liquidGlass) {
                drawLiquidGlass(canvas, bounds, radius);
                return;
            }
            paint.setShader(null);
            paint.setStrokeWidth(stroke);
            int color = focused ? 0xD9000000 : selectedColor;
            int alpha = ((color >>> 24) * drawableAlpha) / 255;
            paint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            canvas.drawRoundRect(outline, radius, radius, paint);
            if (focused) {
                float brightStroke = focusStrokePx;
                paint.setStrokeWidth(brightStroke);
                int focusAlpha = ((focusColor >>> 24) * drawableAlpha) / 255;
                paint.setColor((focusColor & 0x00FFFFFF) | (focusAlpha << 24));
                canvas.drawRoundRect(outline, radius, radius, paint);
            }
        }

        private void drawLiquidGlass(Canvas canvas, Rect bounds, float radius) {
            if (focused) {
                for (int i = 1; i <= 2; i++) {
                    float o = i * density;
                    specRect.set(outline.left - o, outline.top - o, outline.right + o, outline.bottom + o);
                    paint.setShader(null);
                    paint.setStrokeWidth(i * 1.6f * density);
                    int glowAlpha = (((0x28 / i) * drawableAlpha) / 255);
                    paint.setColor((glowAlpha << 24) | (focusColor & 0x00FFFFFF));
                    canvas.drawRoundRect(specRect, radius + o, radius + o, paint);
                }
                paint.setShader(null);
                paint.setStrokeWidth(focusKeylinePx);
                paint.setColor((((0xC8 * drawableAlpha) / 255) << 24));
                canvas.drawRoundRect(outline, radius, radius, paint);

                paint.setStrokeWidth(focusStrokePx);
                paint.setAlpha(drawableAlpha);
                paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                        new int[]{0xFFFFFFFF, focusColor, 0xFF64D2FF},
                        new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(outline, radius, radius, paint);
                paint.setShader(null);
                paint.setAlpha(255);
            } else {
                paint.setStrokeWidth(quietStrokePx);
                paint.setAlpha(drawableAlpha);
                paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                        new int[]{0xCCFFFFFF, selectedColor, 0x9964D2FF},
                        new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(outline, radius, radius, paint);
                paint.setShader(null);
                paint.setAlpha(255);
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
        if (t.isLiquidGlass()) {
            sel.addState(new int[]{-android.R.attr.state_enabled},
                    glassRect(radius, d, 0x1AFFFFFF, 0x0DFFFFFF, 0x14, 0x28FFFFFF, 0x12FFFFFF, 0x209BE4FF, 1.0f));
            sel.addState(new int[]{android.R.attr.state_focused},
                    glassRect(radius, d, 0xEB354C66, 0xD91E2D40, 0x5C, 0xFFFFFFFF, 0xCC9BE4FF, 0xFF64D2FF, 1.5f));
            sel.addState(new int[]{android.R.attr.state_pressed},
                    glassRect(radius, d, 0xF03D5673, 0xE625374D, 0x50, 0xFFFFFFFF, 0xCC9BE4FF, 0xFF64D2FF, 1.25f));
            sel.addState(new int[]{},
                    glassRect(radius, d, t.pillFillTop, t.pillFillBottom, 0x46, 0xC4FFFFFF, 0x3DFFFFFF, 0x8C64D2FF, 1.0f));
        } else {
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
        }

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
        if (t.isLiquidGlass()) {
            sel.addState(new int[]{android.R.attr.state_focused},
                    glassRect(radius, d, 0xEB354C66, 0xD91E2D40, 0x5C, 0xFFFFFFFF, 0xCC9BE4FF, 0xFF64D2FF, 1.5f));
            sel.addState(new int[]{android.R.attr.state_pressed},
                    glassRect(radius, d, 0xF03D5673, 0xE625374D, 0x50, 0xFFFFFFFF, 0xCC9BE4FF, 0xFF64D2FF, 1.25f));
            sel.addState(new int[]{},
                    glassRect(radius, d, 0x5464D2FF, 0x2664D2FF, 0x4A, 0xCCFFFFFF, 0x809BE4FF, 0xB364D2FF, 1.35f));
        } else {
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
        }

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
        if (t.isLiquidGlass()) {
            sel.addState(new int[]{android.R.attr.state_focused},
                    glassRect(radius, d, 0xEB1E2F47, 0xEB121E30, 0x3D, 0xFFFFFFFF, 0xB39BE4FF, 0xFF64D2FF, 1.5f));
            sel.addState(new int[]{},
                    glassRect(radius, d, 0xD9121D2E, 0xD90B121D, 0x2A, 0x80FFFFFF, 0x30FFFFFF, 0x529BE4FF, 1.0f));
        } else {
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
        }

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
        if (t.isLiquidGlass()) {
            return glassRect(radius, d, 0xEC162234, 0xF20D1624, 0x2C,
                    0xB3FFFFFF, 0x38FFFFFF, 0x8C64D2FF, 1.15f);
        }
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
        if (t.isLiquidGlass()) {
            sel.addState(new int[]{android.R.attr.state_focused},
                    glassOval(d, 0xEB354C66, 0xD91E2D40, 0x5C, 0xFFFFFFFF, 0xCC9BE4FF, 0xFF64D2FF, 1.5f));
            sel.addState(new int[]{android.R.attr.state_pressed},
                    glassOval(d, 0xF03D5673, 0xE625374D, 0x50, 0xFFFFFFFF, 0xCC9BE4FF, 0xFF64D2FF, 1.25f));
            sel.addState(new int[]{},
                    glassOval(d, t.pillFillTop, t.pillFillBottom, 0x48, 0xC4FFFFFF, 0x40FFFFFF, 0x8C64D2FF, 1.0f));
        } else {
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
        }

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
        int radius = Math.round(14f * d);
        if (t.isLiquidGlass()) {
            return glassRect(radius, d, 0x46FFFFFF, 0x18FFFFFF, 0x42,
                    0xB8FFFFFF, 0x36FFFFFF, 0x8064D2FF, 1.0f);
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{t.pillFillTop, t.pillFillBottom});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
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
        if (t.isLiquidGlass()) {
            sel.addState(new int[]{android.R.attr.state_focused},
                    glassRect(radius, d, 0xEB354C66, 0xD91E2D40, 0x50,
                            0xFFFFFFFF, 0xCC9BE4FF, 0xFF64D2FF, 1.35f));
            sel.addState(new int[]{android.R.attr.state_pressed},
                    glassRect(radius, d, 0xF03D5673, 0xE625374D, 0x48,
                            0xFFFFFFFF, 0xCC9BE4FF, 0xFF64D2FF, 1.2f));
        } else {
            GradientDrawable focused = new GradientDrawable();
            focused.setShape(GradientDrawable.RECTANGLE);
            focused.setCornerRadius(radius);
            focused.setColor(t.controlFocusFill);
            focused.setStroke(strokeFocus, t.controlFocusStroke);
            sel.addState(new int[]{android.R.attr.state_focused}, focused);
            sel.addState(new int[]{android.R.attr.state_pressed}, focused);
        }

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

    /** Rounded surface used behind the Home dock and the preview's dock mock. */
    static Drawable dockBand(Context c, LauncherTheme t) {
        float d = c.getResources().getDisplayMetrics().density;
        int radius = c.getResources().getDimensionPixelSize(R.dimen.panel_radius);
        if (t.isLiquidGlass()) {
            return glassRect(radius, d, 0x52FFFFFF, 0x241B293D, 0x48,
                    0xD9FFFFFF, 0x4DFFFFFF, 0xA664D2FF, 1.25f);
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.dockBand);
        g.setStroke(Math.max(1, (int) d), t.dockEdge);
        g.setCornerRadius(radius);
        return g;
    }

    /** Themed 2dp horizontal progress bar for Continue Watching cards and previews. */
    static Drawable progressBarDrawable(Context c, LauncherTheme t) {
        float d = c.getResources().getDisplayMetrics().density;
        float corner = 1.5f * d;

        GradientDrawable track = new GradientDrawable();
        track.setShape(GradientDrawable.RECTANGLE);
        track.setColor(t.isLight() ? 0x330B1220 : 0x38FFFFFF);
        track.setCornerRadius(corner);

        int start = t.isLiquidGlass() ? 0xFFFAFDFE : t.accent;
        int end = t.isLiquidGlass() ? t.accent : t.focusRing;
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{start, end});
        fill.setShape(GradientDrawable.RECTANGLE);
        fill.setCornerRadius(corner);

        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{track, clip});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        return layer;
    }

    /** High-contrast circular badge behind the active star icon on theme cards. */
    static Drawable themeActiveBadgeBackground(Context c, LauncherTheme t) {
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(t.accent);
        g.setStroke(Math.max(1, Math.round(d)), t.isLight() ? 0x400B1220 : 0xCCFFFFFF);
        return g;
    }

    private static Drawable glassRect(int radiusPx, float density,
                                      int fillTop, int fillBottom, int sheenAlpha,
                                      int rimStart, int rimMid, int rimEnd, float strokeDp) {
        return new LiquidGlassDrawable(new LiquidGlassDrawable.Spec(
                false, radiusPx, density, fillTop, fillBottom, sheenAlpha, rimStart, rimMid, rimEnd, strokeDp));
    }

    private static Drawable glassOval(float density,
                                      int fillTop, int fillBottom, int sheenAlpha,
                                      int rimStart, int rimMid, int rimEnd, float strokeDp) {
        return new LiquidGlassDrawable(new LiquidGlassDrawable.Spec(
                true, 0f, density, fillTop, fillBottom, sheenAlpha, rimStart, rimMid, rimEnd, strokeDp));
    }

    /** Multi-layer optical glass surface: frosted body, top caustic dome sheen, inner rim and Fresnel stroke. */
    private static final class LiquidGlassDrawable extends Drawable {
        static final class Spec extends ConstantState {
            final boolean oval;
            final float cornerRadiusPx;
            final float density;
            final int fillTop;
            final int fillBottom;
            final int sheenAlpha;
            final int rimStart;
            final int rimMid;
            final int rimEnd;
            final float strokeDp;

            Spec(boolean oval, float cornerRadiusPx, float density,
                 int fillTop, int fillBottom, int sheenAlpha,
                 int rimStart, int rimMid, int rimEnd, float strokeDp) {
                this.oval = oval;
                this.cornerRadiusPx = cornerRadiusPx;
                this.density = density;
                this.fillTop = fillTop;
                this.fillBottom = fillBottom;
                this.sheenAlpha = sheenAlpha;
                this.rimStart = rimStart;
                this.rimMid = rimMid;
                this.rimEnd = rimEnd;
                this.strokeDp = strokeDp;
            }

            @Override
            public Drawable newDrawable() {
                return new LiquidGlassDrawable(this);
            }

            @Override
            public Drawable newDrawable(android.content.res.Resources res) {
                return new LiquidGlassDrawable(this);
            }

            @Override
            public int getChangingConfigurations() {
                return 0;
            }
        }

        private final Spec spec;
        private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF innerRect = new RectF();
        private final RectF sheenRect = new RectF();
        private final Path clipPath = new Path();
        private int drawableAlpha = 255;
        private int lastW = -1;
        private int lastH = -1;

        LiquidGlassDrawable(Spec spec) {
            this.spec = spec;
            bodyPaint.setStyle(Paint.Style.FILL);
            sheenPaint.setStyle(Paint.Style.FILL);
            innerRimPaint.setStyle(Paint.Style.STROKE);
            innerRimPaint.setStrokeWidth(Math.max(1f, spec.density));
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeJoin(Paint.Join.ROUND);
            rimPaint.setStrokeWidth(spec.strokeDp > 0f ? Math.max(1f, spec.strokeDp * spec.density) : 0f);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            lastW = -1;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) return;

            float strokeW = rimPaint.getStrokeWidth();
            float half = spec.strokeDp > 0f ? strokeW * 0.5f : 0f;
            rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
            if (rect.width() <= 0f || rect.height() <= 0f) return;

            float r = spec.oval
                    ? Math.min(rect.width(), rect.height()) * 0.5f
                    : Math.max(0f, Math.min(spec.cornerRadiusPx - half, Math.min(rect.width(), rect.height()) * 0.5f));

            if (w != lastW || h != lastH) {
                lastW = w;
                lastH = h;
                bodyPaint.setShader(new LinearGradient(b.left, b.top, b.left, b.bottom,
                        spec.fillTop, spec.fillBottom, Shader.TileMode.CLAMP));
                float sheenBottom = rect.top + rect.height() * 0.48f;
                int sheenTopColor = (Math.max(0, Math.min(255, spec.sheenAlpha)) << 24) | 0x00FFFFFF;
                sheenPaint.setShader(new LinearGradient(rect.left, rect.top, rect.left, sheenBottom,
                        sheenTopColor, 0x00FFFFFF, Shader.TileMode.CLAMP));
                if (spec.strokeDp > 0f) {
                    innerRimPaint.setShader(new LinearGradient(rect.left, rect.top, rect.left,
                            rect.top + rect.height() * 0.65f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    rimPaint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                            new int[]{spec.rimStart, spec.rimMid, spec.rimEnd},
                            new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
                }
            }

            bodyPaint.setAlpha(drawableAlpha);
            if (spec.oval) canvas.drawOval(rect, bodyPaint);
            else canvas.drawRoundRect(rect, r, r, bodyPaint);

            if (spec.sheenAlpha > 0) {
                clipPath.reset();
                if (spec.oval) clipPath.addOval(rect, Path.Direction.CW);
                else clipPath.addRoundRect(rect, r, r, Path.Direction.CW);
                int saved = canvas.save();
                canvas.clipPath(clipPath);
                sheenRect.set(rect.left - rect.width() * 0.08f, rect.top - rect.height() * 0.14f,
                        rect.right + rect.width() * 0.08f, rect.top + rect.height() * 0.52f);
                sheenPaint.setAlpha(drawableAlpha);
                canvas.drawOval(sheenRect, sheenPaint);
                canvas.restoreToCount(saved);
            }

            if (spec.strokeDp > 0f) {
                float inset = Math.max(1f, spec.density);
                innerRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
                if (innerRect.width() > 0f && innerRect.height() > 0f) {
                    innerRimPaint.setAlpha(drawableAlpha);
                    float ir = Math.max(0f, r - inset);
                    if (spec.oval) canvas.drawOval(innerRect, innerRimPaint);
                    else canvas.drawRoundRect(innerRect, ir, ir, innerRimPaint);
                }

                rimPaint.setAlpha(drawableAlpha);
                if (spec.oval) canvas.drawOval(rect, rimPaint);
                else canvas.drawRoundRect(rect, r, r, rimPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            drawableAlpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            bodyPaint.setColorFilter(colorFilter);
            sheenPaint.setColorFilter(colorFilter);
            innerRimPaint.setColorFilter(colorFilter);
            rimPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public ConstantState getConstantState() {
            return spec;
        }
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
