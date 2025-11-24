package test.weather.sdk;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.ArrayList;

/**
 * Фоновая задача для режима polling — обновляет погоду для всех кэшированных городов.
 * */

public class PollingTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(PollingTask.class);

    private final WeatherService service;
    private final OkHttpClient client;
    private final String apiKey;
    private final Map<String, CacheEntry> cache;
    private volatile boolean running = true;
    private final long updateInterval;

    public PollingTask(WeatherService service, OkHttpClient client, String apiKey,
                       Map<String, CacheEntry> cache, long updateInterval) {
        this.service = service;
        this.client = client;
        this.apiKey = apiKey;
        this.cache = cache;
        this.updateInterval = updateInterval;
    }

    @Override
    public void run() {
        while (running) {
            try {
                // Создаем копию ключей для избежания ConcurrentModificationException
                ArrayList<String> cities = new ArrayList<>(cache.keySet());
                for (String city : cities) {
                    try {
                        WeatherResponse fresh = service.fetchWeatherFromAPI(client, city, apiKey);
                        cache.put(city, new CacheEntry(fresh, System.currentTimeMillis() / 1000));
                    } catch (WeatherException e) {
                        // Логируем ошибку, но продолжаем обработку других городов
                        log.error("Ошибка обновления в polling-режиме для города {}", city + ": " + e.getMessage());
                    }
                }
                Thread.sleep(updateInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void stop() {
        running = false;
    }
}
