package test.weather.sdk;

/**
 * Кастомное исключение для ошибок SDK.
 */
public class WeatherException extends Exception {
    public WeatherException(String message) {
        super(message);
    }

    public WeatherException(String message, Throwable cause) {
        super(message, cause);
    }
}
