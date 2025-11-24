package test.weather.sdk;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class WeatherServiceTest {

    private MockWebServer server;
    private String baseUrl;
    private WeatherService service;
    private final String API_KEY = "test-api-key";

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        baseUrl = "http://localhost:" + server.getPort() + "/data/2.5/weather";
    }

    @AfterEach
    void tearDown() throws IOException {
        if (service != null) {
            service.close();
        }
        server.shutdown();
    }

    @Test
    void getWeather_validCity_returnsResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setBody(Fixtures.VALID_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        service = new WeatherService(API_KEY, Mode.ON_DEMAND, baseUrl + "?q=%s&appid=%s&units=metric");
        WeatherResponse response = service.getWeather("London");

        assertNotNull(response);
        assertEquals("London", response.name);
        assertEquals(282.55, response.temperature.temp);
        assertEquals(100, response.temperature.humidity);
        assertEquals("Clouds", response.weather.main);
    }

    @Test
    void getWeather_cachedResponse_returnsFromCache() throws Exception {
        server.enqueue(new MockResponse()
                .setBody(Fixtures.VALID_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        service = new WeatherService(API_KEY, Mode.ON_DEMAND, baseUrl + "?q=%s&appid=%s&units=metric");
        service.getWeather("London");
        assertEquals(1, server.getRequestCount());

        service.getWeather("London");
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void getWeather_expiredCache_fetchesNewData() throws Exception {
        server.enqueue(new MockResponse()
                .setBody(Fixtures.VALID_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        server.enqueue(new MockResponse()
                .setBody(Fixtures.ANOTHER_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        service = new WeatherService(API_KEY, Mode.ON_DEMAND, baseUrl + "?q=%s&appid=%s&units=metric");

        service.getWeather("London");
        assertEquals(1, server.getRequestCount());

        forceExpireCacheEntry("London");

        service.getWeather("London");
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void getWeather_invalidCity_throwsException() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody(Fixtures.ERROR_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        service = new WeatherService(API_KEY, Mode.ON_DEMAND, baseUrl + "?q=%s&appid=%s&units=metric");

        WeatherException exception = assertThrows(WeatherException.class, () -> {
            service.getWeather("InvalidCity");
        });
        assertTrue(exception.getMessage().contains("404"));
    }

    @Test
    void getWeather_invalidApiKey_throwsException() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody(Fixtures.UNAUTHORIZED_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        service = new WeatherService("invalid-key", Mode.ON_DEMAND, baseUrl + "?q=%s&appid=%s&units=metric");

        WeatherException exception = assertThrows(WeatherException.class, () -> {
            service.getWeather("London");
        });
        assertTrue(exception.getMessage().contains("401"));
    }

    @Test
    void pollingMode_updatesCacheInBackground() throws Exception {
        server.enqueue(new MockResponse()
                .setBody(Fixtures.VALID_RESPONSE)
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse()
                .setBody(Fixtures.ANOTHER_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        // Используем короткий интервал для теста (500 мс)
        service = new WeatherService(API_KEY, Mode.POLLING, baseUrl + "?q=%s&appid=%s&units=metric", 500);
        service.getWeather("London");
        assertEquals(1, server.getRequestCount(), "Ожидается 1 запрос при первом вызове");

        // Ожидаем минимум 2 запроса (первоначальный + фоновое обновление)
        boolean conditionMet = waitForCondition(
                () -> server.getRequestCount() >= 2,
                1500, // Таймаут 1.5 секунды
                100   // Проверяем каждые 100 мс
        );
        assertTrue(conditionMet, "Ожидается минимум 2 запроса за 1.5 секунды");
    }

    @Test
    void close_stopsPollingThread() throws Exception {
        server.enqueue(new MockResponse()
                .setBody(Fixtures.VALID_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        service = new WeatherService(API_KEY, Mode.POLLING, baseUrl + "?q=%s&appid=%s&units=metric");
        service.getWeather("London");

        // Даем потоку время запуститься
        boolean started = waitForCondition(service::isPollingThreadAlive, 3000, 100);
        assertTrue(started, "Поток не запустился за 3 секунды");

        service.close();
        boolean stopped = waitForCondition(() -> !service.isPollingThreadAlive(), 3000, 100);
        assertTrue(stopped, "Фоновый поток не остановился в течение 3 секунд");
    }

    @Test
    void cacheEviction_removesOldestEntries() throws Exception {
        for (int i = 0; i < 12; i++) {
            server.enqueue(new MockResponse()
                    .setBody(Fixtures.VALID_RESPONSE.replace("London", "City" + (i % 11)))
                    .addHeader("Content-Type", "application/json"));
        }

        service = new WeatherService(API_KEY, Mode.ON_DEMAND, baseUrl + "?q=%s&appid=%s&units=metric");

        for (int i = 0; i < 11; i++) {
            service.getWeather("City" + i);
        }

        assertEquals(10, service.getCacheSize(), "Ожидается 10 записей после eviction");

        int initialRequests = server.getRequestCount();
        service.getWeather("City0");
        assertEquals(initialRequests + 1, server.getRequestCount(),
                "City0 должен вызвать новый запрос (отсутствует в кэше)");

        initialRequests = server.getRequestCount();
        service.getWeather("City10");
        assertEquals(initialRequests, server.getRequestCount(),
                "City10 должен браться из кэша (не вызывает новый запрос)");
    }

    private boolean waitForCondition(BooleanSupplier condition, long timeoutMs, long intervalMs) {
        long startTime = System.currentTimeMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void forceExpireCacheEntry(String city) {
        long expiredTimestamp = (System.currentTimeMillis() / 1000) - 601;
        service.updateCacheTimestamp(city, expiredTimestamp);
    }

    static class Fixtures {
        static final String VALID_RESPONSE = """
                {
                  "coord": {"lon": -0.1257, "lat": 51.5085},
                  "weather": [{"id": 521, "main": "Clouds", "description": "shower rain", "icon": "09d"}],
                  "main": {
                    "temp": 282.55,
                    "feels_like": 281.86,
                    "temp_min": 280.37,
                    "temp_max": 284.26,
                    "pressure": 1023,
                    "humidity": 100
                  },
                  "visibility": 10000,
                  "wind": {"speed": 4.63, "deg": 330},
                  "dt": 1704063658,
                  "sys": {
                    "type": 2,
                    "id": 2019646,
                    "country": "GB",
                    "sunrise": 1704063287,
                    "sunset": 1704095739
                  },
                  "timezone": 0,
                  "id": 2643743,
                  "name": "London",
                  "cod": 200
                }""";

        static final String ANOTHER_RESPONSE = """
                {
                  "coord": {"lon": 37.6156, "lat": 55.7558},
                  "weather": [{"id": 800, "main": "Clear", "description": "clear sky", "icon": "01d"}],
                  "main": {
                    "temp": 273.15,
                    "feels_like": 270.15,
                    "temp_min": 270.15,
                    "temp_max": 275.15,
                    "pressure": 1015,
                    "humidity": 80
                  },
                  "visibility": 10000,
                  "wind": {"speed": 3.6, "deg": 180},
                  "dt": 1704063658,
                  "sys": {
                    "sunrise": 1704045600,
                    "sunset": 1704079200
                  },
                  "timezone": 10800,
                  "id": 524901,
                  "name": "Moscow",
                  "cod": 200
                }""";

        static final String ERROR_RESPONSE = """
                {
                  "cod": "404",
                  "message": "city not found"
                }""";

        static final String UNAUTHORIZED_RESPONSE = """
                {
                  "cod": 401,
                  "message": "Invalid API key"
                }""";
    }
}
