package test.weather.sdk;

/**
 * Элемент кэша: хранит данные и временную метку.
 */
public class CacheEntry {
    public final WeatherResponse response;
    public final long timestampSeconds; // время получения в секундах

    public CacheEntry(WeatherResponse response, long timestampSeconds) {
        this.response = response;
        this.timestampSeconds = timestampSeconds;
    }

    public boolean isExpired() {
        return (System.currentTimeMillis() / 1000 - timestampSeconds) >= 600; // 10 минут
    }
}
