package test.weather.sdk;

/**
 * Стандартизированный объект ответа SDK.
 */
public class WeatherResponse {
    public Weather weather;
    public Temperature temperature;
    public int visibility;
    public Wind wind;
    public long datetime;
    public Sys sys;
    public int timezone;
    public String name;

    public static class Weather {
        public String main;
        public String description;
    }

    public static class Temperature {
        public double temp;
        public double feels_like;
        public int humidity;
    }

    public static class Wind {
        public double speed;
    }

    public static class Sys {
        public long sunrise;
        public long sunset;
    }
}
