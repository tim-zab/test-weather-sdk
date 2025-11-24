package test.weather.sdk.examples;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.weather.sdk.Mode;
import test.weather.sdk.WeatherSDK;
import test.weather.sdk.WeatherResponse;
import test.weather.sdk.WeatherException;

/**
 * Пример использования WeatherSDK.
 * Демонстрирует оба режима: ON_DEMAND и POLLING.
 */
public class ExampleUsage {
    private static final Logger log = LoggerFactory.getLogger(ExampleUsage.class);

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().load();
        String apiKey = dotenv.get("OPENWEATHER_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.error("Ошибка: установите переменную окружения OPENWEATHER_API_KEY");
            log.error("Используйте OPENWEATHER_API_KEY=ваш_ключ");
            return;
        }

        log.info("=== Режим ON_DEMAND ===");
        try (WeatherSDK sdkOnDemand = WeatherSDK.getInstance(apiKey, Mode.ON_DEMAND)) {
            fetchAndPrintWeather(sdkOnDemand, "London");
            log.info("Повторный запрос (кэш):");
            fetchAndPrintWeather(sdkOnDemand, "London");
        } catch (Exception e) {
            log.error("Ошибка в режиме ON_DEMAND: {}", e.getMessage(), e);
        }

        log.info("=== Режим POLLING ===");
        try (WeatherSDK sdkPolling = WeatherSDK.getInstance(apiKey, Mode.POLLING)) {
            fetchAndPrintWeather(sdkPolling, "Moscow");

            log.info("Ожидание 5 секунд (фоновое обновление активно)...");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            log.info("Повторный запрос (может быть обновлено в фоне):");
            fetchAndPrintWeather(sdkPolling, "Moscow");
        } catch (Exception e) {
            log.error("Ошибка в режиме POLLING: {}", e.getMessage(), e);
        }

        log.info("SDK закрыт. Приложение завершено.");
    }

    private static void fetchAndPrintWeather(WeatherSDK sdk, String city) {
        try {
            WeatherResponse weather = sdk.getWeather(city);
            log.info("Город: {}", weather.name);
            log.info("Погода: {} ({})", weather.weather.main, weather.weather.description);
            log.info("Температура: {:.1f}°C (ощущается как {:.1f}°C, влажность: {}%)",
                    weather.temperature.temp, weather.temperature.feels_like, weather.temperature.humidity);
            log.info("Видимость: {} м, Ветер: {:.1f} м/с", weather.visibility, weather.wind.speed);
            log.info("Время данных: {}", new java.util.Date(weather.datetime * 1000));
        } catch (WeatherException e) {
            log.error("Ошибка при получении погоды для {}: {}", city, e.getMessage(), e);
        }
    }
}
