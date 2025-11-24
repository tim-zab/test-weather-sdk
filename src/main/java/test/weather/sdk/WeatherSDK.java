package test.weather.sdk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Главный класс SDK — потокобезопасный синглтон на основе API-ключа.
 */
public class WeatherSDK implements AutoCloseable {
    private static final ConcurrentHashMap<String, WeatherSDK> INSTANCES = new ConcurrentHashMap<>();

    private final String trimmedApiKey;
    private final Mode mode;
    private final WeatherService service;

    private WeatherSDK(String apiKey, Mode mode) {
        this.trimmedApiKey = apiKey.trim();
        this.mode = mode;
        this.service = new WeatherService(trimmedApiKey, mode);
    }

    public static WeatherSDK getInstance(String apiKey, Mode mode) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API-ключ не может быть пустым");
        }
        String trimmedKey = apiKey.trim();

        WeatherSDK existing = INSTANCES.get(trimmedKey);
        if (existing != null) {
            if (existing.mode != mode) {
                throw new IllegalArgumentException(
                        "Режим для этого API-ключа уже установлен как " + existing.mode +
                                ". Нельзя изменить режим после первого создания."
                );
            }
            return existing;
        }
        return INSTANCES.computeIfAbsent(trimmedKey, k -> new WeatherSDK(apiKey, mode));
    }

    public WeatherResponse getWeather(String city) throws WeatherException {
        return service.getWeather(city);
    }

    @Override
    public void close() {
        service.close();
        INSTANCES.remove(trimmedApiKey);
    }

}
