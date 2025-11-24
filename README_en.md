# Weather SDK for OpenWeather API

A lightweight, thread-safe Java SDK for accessing site [OpenWeather API](https://openweathermap.org/api).

## Features
- Java 17
- Singleton per API key
- Two modes: `ON_DEMAND` and `POLLING`
- Caching (10 cities max, 10 min TTL)
- Complete data model
- Clear exceptions
- Proper resource cleanup

## Installation

Add to your `pom.xml`:
```
<dependency>
    <groupId>com.weather.sdk</groupId>
    <artifactId>weather-sdk</artifactId>
    <version>1.0.1</version>
</dependency>
```
#### Usage
You can see example usage in examples/ExampleUsage.java

#### Modes
ON_DEMAND: Fetch only when requested.
POLLING: Background updates every 10 minutes.
Important: Mode is fixed on first instance creation for a given API key. Attempting to create an instance with a different mode for the same key will throw an exception.

#### Exception Handling
All errors throw WeatherException with descriptive messages.

#### Caching
Maximum 10 cities in cache
Entry TTL: 10 minutes
Automatic cleanup of expired entries

#### Testing
In WeatherServiceTest are used basic testing scenarios.
