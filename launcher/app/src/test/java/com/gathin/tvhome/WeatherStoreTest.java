package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeatherStoreTest {

    @Test
    public void testWeatherDataValidation() {
        WeatherStore.WeatherData valid = new WeatherStore.WeatherData(18.5f, 1, "Bogotá", 1000L, false);
        assertTrue(valid.isValid());
        assertEquals("19° Bogotá", valid.getFormatted());
        assertEquals(R.drawable.ic_weather_sun, valid.getIconResId());

        WeatherStore.WeatherData invalid = new WeatherStore.WeatherData(-999f, -1, "Bogotá", 0L, true);
        assertFalse(invalid.isValid());
        assertEquals("", invalid.getFormatted());
    }

    @Test
    public void testWeatherIconMappings() {
        WeatherStore.WeatherData sun = new WeatherStore.WeatherData(22f, 0, "Bogotá", 1000L, false);
        assertEquals(R.drawable.ic_weather_sun, sun.getIconResId());

        WeatherStore.WeatherData cloud = new WeatherStore.WeatherData(18f, 3, "Bogotá", 1000L, false);
        assertEquals(R.drawable.ic_weather_cloud, cloud.getIconResId());

        WeatherStore.WeatherData rain = new WeatherStore.WeatherData(15f, 61, "Bogotá", 1000L, false);
        assertEquals(R.drawable.ic_weather_rain, rain.getIconResId());

        WeatherStore.WeatherData storm = new WeatherStore.WeatherData(14f, 95, "Bogotá", 1000L, false);
        assertEquals(R.drawable.ic_weather_storm, storm.getIconResId());
    }

    @Test
    public void testCacheTtlLogic() {
        long now = 10_000_000L;
        long recent = now - (1000L * 30 * 60); // 30 minutes ago
        long old = now - (1000L * 60 * 60 * 2); // 2 hours ago

        assertTrue(now - recent < WeatherStore.CACHE_TTL_MS);
        assertFalse(now - old < WeatherStore.CACHE_TTL_MS);
    }
}
