package test.weather.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Основной сервис погоды — выполняет запросы, кэширует, обновляет.
 */
public class WeatherService {
    private static final String DEFAULT_API_URL;
    private static final long UPDATE_INTERVAL;

    static {
        Dotenv dotenv = Dotenv.load();
        DEFAULT_API_URL = dotenv.get("DEFAULT_API_URL",
                "https://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s&units=metric");
        String intervalStr = dotenv.get("UPDATE_INTERVAL", "600000");
        UPDATE_INTERVAL = Long.parseLong(intervalStr);
    }

    private final String apiKey;
    private final String apiUrl;
    private final long updateInterval;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private Thread pollingThread;
    private PollingTask pollingTask;

    public WeatherService(String apiKey, Mode mode) {
        this(apiKey, mode, DEFAULT_API_URL, UPDATE_INTERVAL);
    }

    public WeatherService(String apiKey, Mode mode, String apiUrl) {
        this(apiKey, mode, apiUrl, UPDATE_INTERVAL);
    }

    public WeatherService(String apiKey, Mode mode, String apiUrl, long updateInterval) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.updateInterval = updateInterval;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        if (mode == Mode.POLLING) {
            startPolling();
        }
    }

    private void startPolling() {
        pollingTask = new PollingTask(this, client, apiKey, cache, updateInterval);
        pollingThread = new Thread(pollingTask, "WeatherSDK-PollingThread");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    public WeatherResponse getWeather(String city) throws WeatherException {
        if (city == null || city.trim().isEmpty()) {
            throw new WeatherException("Название города не может быть пустым");
        }
        String trimmedCity = city.trim();

        CacheEntry entry = cache.get(trimmedCity);
        if (entry != null && !entry.isExpired()) {
            return entry.response;
        }

        WeatherResponse fresh = fetchWeatherFromAPI(client, trimmedCity, apiKey);
        cache.put(trimmedCity, new CacheEntry(fresh, System.currentTimeMillis() / 1000));
        evictOldEntries();
        return fresh;
    }

    void updateCacheTimestamp(String city, long timestamp) {
        CacheEntry entry = cache.get(city);
        if (entry != null) {
            cache.put(city, new CacheEntry(entry.response, timestamp));
        }
    }

    boolean isPollingThreadAlive() {
        return pollingThread != null && pollingThread.isAlive();
    }

    int getCacheSize() {
        return cache.size();
    }

    private void evictOldEntries() {
        cache.entrySet().removeIf(e -> e.getValue().isExpired());

        if (cache.size() > 10) {
            cache.entrySet().stream()
                    .min((a, b) -> Long.compare(a.getValue().timestampSeconds, b.getValue().timestampSeconds))
                    .ifPresent(e -> cache.remove(e.getKey()));
        }
    }

    public WeatherResponse fetchWeatherFromAPI(OkHttpClient client, String city, String key) throws WeatherException {
        String url = String.format(apiUrl, city, key);
        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) {
                throw new WeatherException("Пустое тело ответа от API");
            }

            String responseBody = response.body().string();
            JsonNode root = mapper.readTree(responseBody);

            if (root.has("cod")) {
                int code = root.get("cod").asInt();
                if (code != 200) {
                    String message = root.has("message") ?
                            root.get("message").asText() : "Неизвестная ошибка API";
                    throw new WeatherException("OpenWeather API error [" + code + "]: " + message);
                }
            }

            WeatherResponse wr = new WeatherResponse();
            wr.name = root.has("name") ? root.get("name").asText() : city;

            JsonNode weatherArr = root.get("weather");
            if (weatherArr != null && !weatherArr.isEmpty()) {
                wr.weather = new WeatherResponse.Weather();
                wr.weather.main = weatherArr.get(0).has("main") ?
                        weatherArr.get(0).get("main").asText() : "Unknown";
                wr.weather.description = weatherArr.get(0).has("description") ?
                        weatherArr.get(0).get("description").asText() : "No description";
            }

            JsonNode main = root.get("main");
            if (main != null) {
                wr.temperature = new WeatherResponse.Temperature();
                wr.temperature.temp = main.has("temp") ? main.get("temp").asDouble() : 0.0;
                wr.temperature.feels_like = main.has("feels_like") ? main.get("feels_like").asDouble() : 0.0;
                wr.temperature.humidity = main.has("humidity") ? main.get("humidity").asInt() : 0;
            }

            wr.visibility = root.has("visibility") ? root.get("visibility").asInt() : 10000;

            JsonNode wind = root.get("wind");
            if (wind != null) {
                wr.wind = new WeatherResponse.Wind();
                wr.wind.speed = wind.has("speed") ? wind.get("speed").asDouble() : 0.0;
            }

            wr.datetime = root.has("dt") ? root.get("dt").asLong() : System.currentTimeMillis() / 1000;

            JsonNode sys = root.get("sys");
            if (sys != null) {
                wr.sys = new WeatherResponse.Sys();
                wr.sys.sunrise = sys.has("sunrise") ? sys.get("sunrise").asLong() : 0L;
                wr.sys.sunset = sys.has("sunset") ? sys.get("sunset").asLong() : 0L;
            }

            wr.timezone = root.has("timezone") ? root.get("timezone").asInt() : 0;

            return wr;
        } catch (IOException e) {
            throw new WeatherException("Ошибка сети при запросе к API: " + e.getMessage(), e);
        }
    }

    public void close() {
        if (pollingTask != null) {
            pollingTask.stop();
        }
        if (pollingThread != null && !pollingThread.isInterrupted()) {
            pollingThread.interrupt();
            try {
                pollingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        client.dispatcher().executorService().shutdown();
        try {
            if (!client.dispatcher().executorService().awaitTermination(1, TimeUnit.SECONDS)) {
                client.dispatcher().executorService().shutdownNow();
            }
        } catch (InterruptedException e) {
            client.dispatcher().executorService().shutdownNow();
            Thread.currentThread().interrupt();
        }
        client.connectionPool().evictAll();
    }
}
