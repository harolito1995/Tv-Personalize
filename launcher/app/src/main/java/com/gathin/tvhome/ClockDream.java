package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: the current Home wallpaper as a full-screen photo, with a thin clock and date
 * over a bottom scrim. The photo drifts slowly so a static frame does not linger. With no
 * wallpaper it falls back to the clock on black. No network, no extra storage.
 */
public class ClockDream extends DreamService {

    private static final long FADE_MS = 1100L;
    private static final long DRIFT_MS = 28_000L;
    private static final float DRIFT_SCALE = 1.06f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeExec = Executors.newSingleThreadExecutor();
    private View content;
    private ImageView photo;
    private ValueAnimator drift;
    private Bitmap bitmap;
    private volatile boolean detached;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        detached = false;
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        photo = findViewById(R.id.dreamPhoto);
        content.setAlpha(0f);
        loadWallpaper();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.animate().alpha(1f).setDuration(FADE_MS).start();
        if (photo.getVisibility() == View.VISIBLE && bitmap != null) startDrift();
    }

    @Override
    public void onDreamingStopped() {
        stopDrift();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        detached = true;
        stopDrift();
        decodeExec.shutdownNow();
        if (bitmap != null) {
            photo.setImageDrawable(null);
            bitmap.recycle();
            bitmap = null;
        }
        super.onDetachedFromWindow();
    }

    private void loadWallpaper() {
        String saved = Prefs.dreamWallpaper(this);
        if (saved == null || saved.isEmpty()) saved = Prefs.wallpaper(this);
        if (saved == null || saved.isEmpty()) return;
        final Uri uri = Uri.parse(saved);
        if (isVideo(uri)) return;

        decodeExec.execute(() -> {
            Bitmap decoded = decode(uri);
            handler.post(() -> {
                if (detached || decoded == null) {
                    if (decoded != null) decoded.recycle();
                    return;
                }
                bitmap = decoded;
                photo.setImageBitmap(decoded);
                photo.setVisibility(View.VISIBLE);
                photo.setAlpha(0f);
                photo.animate().alpha(1f).setDuration(FADE_MS).start();
                startDrift();
            });
        });
    }

    private boolean isVideo(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path != null) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv")
                    || lower.endsWith(".m4v")) {
                return true;
            }
        }
        String type = getContentResolver().getType(uri);
        return type != null && type.startsWith("video/");
    }

    private Bitmap decode(Uri uri) {
        try {
            Resources res = getResources();
            int max = Math.max(res.getDisplayMetrics().widthPixels,
                    res.getDisplayMetrics().heightPixels);
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = "file".equals(uri.getScheme())
                        ? ImageDecoder.createSource(new File(uri.getPath()))
                        : ImageDecoder.createSource(getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                    int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (largest > max) decoder.setTargetSampleSize(Math.max(1, largest / max));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void startDrift() {
        stopDrift();
        photo.setScaleX(1f);
        photo.setScaleY(1f);
        drift = ValueAnimator.ofFloat(1f, DRIFT_SCALE);
        drift.setDuration(DRIFT_MS);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setInterpolator(new LinearInterpolator());
        drift.addUpdateListener(a -> {
            float scale = (float) a.getAnimatedValue();
            photo.setScaleX(scale);
            photo.setScaleY(scale);
        });
        drift.start();
    }

    private void stopDrift() {
        if (drift != null) {
            drift.cancel();
            drift = null;
        }
    }
}
