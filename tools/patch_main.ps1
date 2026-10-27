$p = "launcher\app\src\main\java\com\harolhome\tv\MainActivity.java"
$content = [System.IO.File]::ReadAllText($p)

# 1. Imports
$importAnchor = "import android.graphics.PorterDuff;"
$importAdd = "import android.graphics.PorterDuff;`r`nimport android.animation.ArgbEvaluator;`r`nimport android.animation.ValueAnimator;`r`nimport android.graphics.RadialGradient;`r`nimport android.graphics.Shader;"
if ($content.Contains($importAnchor) -and -not $content.Contains("import android.animation.ArgbEvaluator;")) {
    $content = $content.Replace($importAnchor, $importAdd)
    Write-Host "1. Imports patched"
}

# 2. Ambient fields
$fieldAnchor = "private android.widget.FrameLayout root;"
$fieldAdd = "private android.widget.FrameLayout root;`r`n    private View ambient;`r`n    private int ambientColor;`r`n    private ValueAnimator ambientAnimator;"
if ($content.Contains($fieldAnchor) -and -not $content.Contains("private View ambient;")) {
    $content = $content.Replace($fieldAnchor, $fieldAdd)
    Write-Host "2. Fields patched"
}

# 3. onCreate view binding
$viewAnchor = "root = findViewById(R.id.root);"
$viewAdd = "root = findViewById(R.id.root);`r`n        ambient = findViewById(R.id.ambient);"
if ($content.Contains($viewAnchor) -and -not $content.Contains("ambient = findViewById(R.id.ambient);")) {
    $content = $content.Replace($viewAnchor, $viewAdd)
    Write-Host "3. View binding patched"
}

# 4. DockBar callbacks
$oldDock = "dock = new DockBar(this, (LinearLayout) findViewById(R.id.dockRow), new DockBar.Callbacks() {
            @Override
            public void onTileFocused(int index) {
                if (index >= 0) lastDock = index;
            }"
$newDock = "dock = new DockBar(this, (LinearLayout) findViewById(R.id.dockRow), new DockBar.Callbacks() {
            @Override
            public void onTileFocused(int index, int color) {
                if (index >= 0) lastDock = index;
                transitionAmbient(color);
            }"
$content = $content.Replace($oldDock.Replace("`r`n", "`n"), $newDock.Replace("`r`n", "`n"))
$content = $content.Replace($oldDock, $newDock)
Write-Host "4. Dock callbacks patched"

# 5. Buttons focus listener
$btnAnchor = "btnWeather.setOnClickListener(v -> showWeather());"
$btnFade = "btnWeather.setOnClickListener(v -> showWeather());`r`n        View.OnFocusChangeListener ambientFadeListener = (v, focused) -> {`r`n            if (focused) fadeOutAmbient();`r`n        };`r`n        btnInputs.setOnFocusChangeListener(ambientFadeListener);`r`n        btnWifi.setOnFocusChangeListener(ambientFadeListener);`r`n        btnSettings.setOnFocusChangeListener(ambientFadeListener);`r`n        if (weatherPill != null) weatherPill.setOnFocusChangeListener(ambientFadeListener);`r`n        btnApps.setOnFocusChangeListener(ambientFadeListener);`r`n        btnSearch.setOnFocusChangeListener(ambientFadeListener);`r`n        btnPersonalize.setOnFocusChangeListener(ambientFadeListener);`r`n        btnThemes.setOnFocusChangeListener(ambientFadeListener);`r`n        btnWeather.setOnFocusChangeListener(ambientFadeListener);"
if ($content.Contains($btnAnchor) -and -not $content.Contains("ambientFadeListener")) {
    $content = $content.Replace($btnAnchor, $btnFade)
    Write-Host "5. Buttons focus patched"
}

# 6. Shelf card focus listener
$shelfAnchor = "card.root.setOnFocusChangeListener((v, focused) -> {"
$shelfAdd = "card.root.setOnFocusChangeListener((v, focused) -> {`r`n            if (focused) fadeOutAmbient();"
if ($content.Contains($shelfAnchor) -and -not $content.Contains("if (focused) fadeOutAmbient();")) {
    $content = $content.Replace($shelfAnchor, $shelfAdd)
    Write-Host "6. Shelf card focus patched"
}

# 7. updateRootBackground and ambient methods
$oldRootBg = "    private void updateRootBackground() {
        boolean covered = wallpaper.getVisibility() == View.VISIBLE
                || wallpaperVideo.getVisibility() == View.VISIBLE;
        root.setBackground(covered ? null
                : ThemeKit.fallback(this, LauncherTheme.byId(Prefs.theme(this))));
    }"

$newRootBg = "    private boolean isWallpaperCovered() {
        return (wallpaper != null && wallpaper.getVisibility() == View.VISIBLE)
                || (wallpaperVideo != null && wallpaperVideo.getVisibility() == View.VISIBLE);
    }

    private void updateRootBackground() {
        boolean covered = isWallpaperCovered();
        root.setBackground(covered ? null
                : ThemeKit.fallback(this, LauncherTheme.byId(Prefs.theme(this))));
        if (covered && ambient != null) {
            stopAmbientAnimation();
            ambient.animate().cancel();
            ambient.setAlpha(0f);
        }
    }

    private void setAmbient(int color) {
        if (ambient == null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.50f, h * 0.65f, w * 0.70f,
                    new int[]{0x85FFFFFF, 0x2EFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        if (isWallpaperCovered()) {
            if (ambient != null) ambient.setAlpha(0f);
            return;
        }
        if (ambient == null) return;
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor && ambient.getAlpha() > 0.95f) return;

        if (ambientColor == 0 || ambient.getAlpha() < 0.05f) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(260).start();
            return;
        }

        ambient.animate().alpha(1f).setDuration(200).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(350);
        ambientAnimator.addUpdateListener(anim -> setAmbient((int) anim.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void fadeOutAmbient() {
        stopAmbientAnimation();
        if (ambient != null) {
            ambient.animate().cancel();
            ambient.animate().alpha(0f).setDuration(300).start();
        }
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }"

$content = $content.Replace($oldRootBg.Replace("`r`n", "`n"), $newRootBg.Replace("`r`n", "`n"))
$content = $content.Replace($oldRootBg, $newRootBg)
Write-Host "7. Root background & ambient methods patched"

# 8. onStop / onDestroy
$onStopOld = "wasStopped = true;"
$onStopNew = "wasStopped = true;`r`n        stopAmbientAnimation();"
if ($content.Contains($onStopOld) -and -not $content.Contains("wasStopped = true;`r`n        stopAmbientAnimation();")) {
    $content = $content.Replace($onStopOld, $onStopNew)
    Write-Host "8. onStop patched"
}

$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($p, $content, $enc)
Write-Host "MainActivity.java saved successfully without BOM"