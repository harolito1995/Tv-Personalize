package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight client for Open-Meteo weather data with strict 3-hour caching,
 * persistent offline fallback, and non-blocking background fetching.
 * Complies with Open-Meteo terms (CC BY 4.0).
 */
public final class WeatherStore {

    private static final String PREFS_NAME = "weather_prefs";
    private static final String KEY_ENABLED = "weather_enabled";
    private static final String KEY_CITY = "weather_city";
    private static final String KEY_LAT = "weather_lat";
    private static final String KEY_LON = "weather_lon";
    private static final String KEY_LAST_TEMP = "weather_last_temp";
    private static final String KEY_LAST_CODE = "weather_last_code";
    private static final String KEY_LAST_TIME = "weather_last_time";

    public static final long CACHE_TTL_MS = 3 * 60 * 60 * 1000L; // 3 hours
    public static final String DEFAULT_CITY = "Bogotá";
    public static final float DEFAULT_LAT = 4.7110f;
    public static final float DEFAULT_LON = -74.0721f;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static final class WeatherData {
        public final float temperature;
        public final int weatherCode;
        public final String cityName;
        public final long timestamp;
        public final boolean isCached;

        public WeatherData(float temperature, int weatherCode, String cityName, long timestamp, boolean isCached) {
            this.temperature = temperature;
            this.weatherCode = weatherCode;
            this.cityName = cityName != null ? cityName : DEFAULT_CITY;
            this.timestamp = timestamp;
            this.isCached = isCached;
        }

        public boolean isValid() {
            return temperature > -100f && weatherCode >= 0;
        }

        public int getIconResId() {
            // WMO Weather interpretation codes (WW)
            // 0, 1: Clear sky, Mainly clear
            if (weatherCode <= 1) return R.drawable.ic_weather_sun;
            // 2, 3: Partly cloudy, overcast; 45, 48: Fog
            if (weatherCode <= 3 || weatherCode == 45 || weatherCode == 48) {
                return R.drawable.ic_weather_cloud;
            }
            // 51..82: Drizzle, rain, showers
            if (weatherCode < 90) return R.drawable.ic_weather_rain;
            // 95..99: Thunderstorm
            return R.drawable.ic_weather_storm;
        }

        public String getFormatted() {
            if (!isValid()) return "";
            return Math.round(temperature) + "° " + cityName;
        }
    }

    public interface WeatherCallback {
        void onWeather(WeatherData data);
    }

    private WeatherStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static String getCity(Context context) {
        if (context == null) return DEFAULT_CITY;
        return prefs(context).getString(KEY_CITY, DEFAULT_CITY);
    }

    public static void setCity(Context context, String city, float lat, float lon) {
        if (context == null) return;
        prefs(context).edit()
                .putString(KEY_CITY, city)
                .putFloat(KEY_LAT, lat)
                .putFloat(KEY_LON, lon)
                .putLong(KEY_LAST_TIME, 0L) // Invalidate cache on city change
                .apply();
    }

    public static WeatherData getCached(Context context) {
        if (context == null) return new WeatherData(-999f, -1, DEFAULT_CITY, 0L, true);
        SharedPreferences p = prefs(context);
        float temp = p.getFloat(KEY_LAST_TEMP, -999f);
        int code = p.getInt(KEY_LAST_CODE, -1);
        long time = p.getLong(KEY_LAST_TIME, 0L);
        String city = p.getString(KEY_CITY, DEFAULT_CITY);
        return new WeatherData(temp, code, city, time, true);
    }

    /**
     * Loads weather data. If cached within 3 hours, returns immediately.
     * Otherwise fetches from Open-Meteo in background thread.
     */
    public static void fetch(Context context, boolean force, WeatherCallback cb) {
        if (context == null || cb == null) return;
        final Context appCtx = context.getApplicationContext();

        if (!isEnabled(appCtx)) {
            MAIN_HANDLER.post(() -> cb.onWeather(new WeatherData(-999f, -1, DEFAULT_CITY, 0L, false)));
            return;
        }

        WeatherData cached = getCached(appCtx);
        long now = System.currentTimeMillis();
        if (!force && cached.isValid() && (now - cached.timestamp < CACHE_TTL_MS)) {
            MAIN_HANDLER.post(() -> cb.onWeather(cached));
            return;
        }

        // Background fetch
        EXECUTOR.execute(() -> {
            WeatherData result = fetchSync(appCtx);
            MAIN_HANDLER.post(() -> cb.onWeather(result));
        });
    }

    public static WeatherData fetchSync(Context context) {
        SharedPreferences p = prefs(context);
        String city = p.getString(KEY_CITY, DEFAULT_CITY);
        float lat = p.getFloat(KEY_LAT, DEFAULT_LAT);
        float lon = p.getFloat(KEY_LON, DEFAULT_LON);

        String urlStr = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto",
                lat, lon);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "tv-vibehack-launcher");

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONObject current = root.optJSONObject("current");
                if (current != null) {
                    float temp = (float) current.optDouble("temperature_2m", -999.0);
                    int weatherCode = current.optInt("weather_code", -1);
                    long now = System.currentTimeMillis();

                    p.edit()
                            .putFloat(KEY_LAST_TEMP, temp)
                            .putInt(KEY_LAST_CODE, weatherCode)
                            .putLong(KEY_LAST_TIME, now)
                            .apply();

                    return new WeatherData(temp, weatherCode, city, now, false);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }

        // On failure, return last cached data if available
        WeatherData last = getCached(context);
        return last.isValid() ? last : new WeatherData(-999f, -1, city, 0L, true);
    }
}
