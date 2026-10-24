package com.harolhome.tv;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.content.SharedPreferences;
import android.location.LocationManager;
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
 * Lightweight client for Open-Meteo weather data with strict one-hour caching,
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

    public static final long CACHE_TTL_MS = 60 * 60 * 1000L; // 1 hour
    private static final long LOCATION_TTL_MS = 12 * 60 * 60 * 1000L;
    private static final long MAX_FIX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final String KEY_LOCATION_TIME = "weather_location_time";
    private static final String KEY_MANUAL_LOCATION = "weather_manual_location";
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
            // WMO codes, with separate symbols for partly cloudy and snow.
            if (weatherCode <= 1) return R.drawable.ic_weather_sun;
            if (weatherCode == 2) return R.drawable.ic_weather_sun_cloud;
            if (weatherCode == 3 || weatherCode == 45 || weatherCode == 48) return R.drawable.ic_weather_cloud;
            if ((weatherCode >= 71 && weatherCode <= 77) || weatherCode == 85 || weatherCode == 86) {
                return R.drawable.ic_weather_snow;
            }
            if (weatherCode >= 95) return R.drawable.ic_weather_storm;
            return R.drawable.ic_weather_rain;
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

    private static final class LocationTarget {
        final String city;
        final float latitude;
        final float longitude;

        LocationTarget(String city, float latitude, float longitude) {
            this.city = city;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /** Use a recent TV location when Android has one, then fall back to network IP geolocation. */
    @SuppressLint("MissingPermission")
    private static LocationTarget resolveLocation(Context context, SharedPreferences p) {
        String savedCity = p.getString(KEY_CITY, DEFAULT_CITY);
        float savedLat = p.getFloat(KEY_LAT, DEFAULT_LAT);
        float savedLon = p.getFloat(KEY_LON, DEFAULT_LON);
        long now = System.currentTimeMillis();
        if (p.getBoolean(KEY_MANUAL_LOCATION, false)) return new LocationTarget(savedCity, savedLat, savedLon);
        long locationTime = p.getLong(KEY_LOCATION_TIME, 0L);
        if (locationTime > 0L && now - locationTime < LOCATION_TTL_MS) {
            return new LocationTarget(savedCity, savedLat, savedLon);
        }

        Location recent = getRecentDeviceLocation(context, now);
        String localCity = recent == null ? null : reverseGeocodeCity(context, recent);
        LocationTarget networkTarget = (recent == null || localCity == null) ? fetchIpLocation() : null;
        if (recent != null) {
            String city = localCity;
            if (city == null && networkTarget != null) city = networkTarget.city;
            if (city == null || city.trim().isEmpty()) city = "Ubicación actual";
            LocationTarget target = new LocationTarget(city, (float) recent.getLatitude(), (float) recent.getLongitude());
            saveLocation(p, target, now);
            return target;
        }
        if (networkTarget != null) {
            saveLocation(p, networkTarget, now);
            return networkTarget;
        }
        return new LocationTarget(savedCity, savedLat, savedLon);
    }

    @SuppressLint("MissingPermission")
    private static Location getRecentDeviceLocation(Context context, long now) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;
        Location best = null;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER,
                LocationManager.GPS_PROVIDER}) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null) continue;
                long age = now - candidate.getTime();
                if (age < -5 * 60 * 1000L || age > MAX_FIX_AGE_MS) continue;
                if (candidate.hasAccuracy() && candidate.getAccuracy() > 100000f) continue;
                if (best == null || candidate.getTime() > best.getTime()) best = candidate;
            } catch (RuntimeException ignored) {
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private static String reverseGeocodeCity(Context context, Location location) {
        try {
            if (!Geocoder.isPresent()) return null;
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            java.util.List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (addresses == null || addresses.isEmpty()) return null;
            Address address = addresses.get(0);
            String city = address.getLocality();
            if (city == null || city.trim().isEmpty()) city = address.getSubAdminArea();
            if (city == null || city.trim().isEmpty()) city = address.getAdminArea();
            return city == null || city.trim().isEmpty() ? null : city.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocationTarget fetchIpLocation() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://ipapi.co/json/").openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "HarolHomeTV");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject json = new JSONObject(body.toString());
            String city = json.optString("city", "").trim();
            double latitude = json.optDouble("latitude", Double.NaN);
            double longitude = json.optDouble("longitude", Double.NaN);
            if (city.isEmpty() || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return null;
            return new LocationTarget(city, (float) latitude, (float) longitude);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void saveLocation(SharedPreferences p, LocationTarget target, long now) {
        p.edit().putString(KEY_CITY, target.city).putFloat(KEY_LAT, target.latitude)
                .putFloat(KEY_LON, target.longitude).putLong(KEY_LOCATION_TIME, now).apply();
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
                .putBoolean(KEY_MANUAL_LOCATION, true)
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
     * Loads weather data. If weather and location data are fresh, returns the cached reading.
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
        SharedPreferences locationPrefs = prefs(appCtx);
        boolean locationStale = !locationPrefs.getBoolean(KEY_MANUAL_LOCATION, false)
                && now - locationPrefs.getLong(KEY_LOCATION_TIME, 0L) >= LOCATION_TTL_MS;
        if (!force && !locationStale && cached.isValid() && (now - cached.timestamp < CACHE_TTL_MS)) {
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
        LocationTarget target = resolveLocation(context.getApplicationContext(), p);
        String city = target.city;
        float lat = target.latitude;
        float lon = target.longitude;

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
            conn.setRequestProperty("User-Agent", "HarolHomeTV");

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
