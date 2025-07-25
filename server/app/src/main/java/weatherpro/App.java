package weatherpro;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.client.WebClient;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

public class App extends AbstractVerticle {

    private MySQLPool client;
    private WebClient webClient;
    private static final String OPENWEATHER_API_KEY = "ebb2b4f6eb7080ef10582240d598fdb7";
    private static final String OPENWEATHER_BASE_URL = "https://api.openweathermap.org/data/2.5";

    @Override
    public void start(Promise<Void> startPromise) throws Exception {

        // Initialize MySQL client
        MySQLConnectOptions connectOptions = new MySQLConnectOptions()
                .setPort(3306)
                .setHost("localhost")
                .setDatabase("weatherpro")
                .setUser("root")
                .setPassword("Feluda@007");

        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(5);

        client = MySQLPool.pool(vertx, connectOptions, poolOptions);
        webClient = WebClient.create(vertx);

        // Initialize database tables
        initializeDatabase()
                .compose(v -> startHttpServer())
                .onComplete(startPromise);
    }

    private Future<Void> initializeDatabase() {
        return client.query("""
            CREATE TABLE IF NOT EXISTS users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(50) UNIQUE NOT NULL,
                email VARCHAR(100) UNIQUE NOT NULL,
                password_hash VARCHAR(255) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """).execute()
                .compose(v -> client.query("""
            CREATE TABLE IF NOT EXISTS user_locations (
                id INT AUTO_INCREMENT PRIMARY KEY,
                user_id INT,
                location VARCHAR(100) NOT NULL,
                display_name VARCHAR(100) NOT NULL,
                latitude DECIMAL(10, 8),
                longitude DECIMAL(11, 8),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """).execute())
                .compose(v -> client.query("""
            CREATE TABLE IF NOT EXISTS weather_alerts (
                id INT AUTO_INCREMENT PRIMARY KEY,
                user_id INT,
                location VARCHAR(100) NOT NULL,
                alert_type VARCHAR(20) NOT NULL,
                condition_type VARCHAR(10) NOT NULL,
                threshold_value DECIMAL(10, 2) NOT NULL,
                is_active BOOLEAN DEFAULT TRUE,
                last_triggered TIMESTAMP NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """).execute())
                .compose(v -> client.query("""
            CREATE TABLE IF NOT EXISTS weather_cache (
                id INT AUTO_INCREMENT PRIMARY KEY,
                location VARCHAR(100) NOT NULL,
                weather_data JSON NOT NULL,
                cached_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX location_idx (location),
                INDEX time_idx (cached_at)
            )
        """).execute())
                .mapEmpty();
    }

    private Future<Void> startHttpServer() {
        HttpServer server = vertx.createHttpServer();
        Router router = createRouter();

        return server
                .requestHandler(router)
                .listen(8080)
                .mapEmpty();
    }

    private Router createRouter() {
        Router router = Router.router(vertx);

        // CORS handling
        Set<String> allowedHeaders = new HashSet<>();
        allowedHeaders.add("x-requested-with");
        allowedHeaders.add("Access-Control-Allow-Origin");
        allowedHeaders.add("origin");
        allowedHeaders.add("Content-Type");
        allowedHeaders.add("accept");
        allowedHeaders.add("Authorization");

        router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders));
        router.route().handler(BodyHandler.create());

        // Authentication routes
        router.post("/api/auth/register").handler(this::handleRegister);
        router.post("/api/auth/login").handler(this::handleLogin);

        // Weather routes
        router.get("/api/weather/:location").handler(this::handleGetWeather);

        // Location management routes
        router.post("/api/locations/save").handler(this::handleSaveLocation);
        router.get("/api/locations/:userId").handler(this::handleGetUserLocations);
        router.delete("/api/locations/:locationId").handler(this::handleDeleteLocation);

        // Alert management routes
        router.post("/api/alerts/create").handler(this::handleCreateAlert);
        router.get("/api/alerts/:userId").handler(this::handleGetUserAlerts);
        router.delete("/api/alerts/:alertId").handler(this::handleDeleteAlert);

        // Health check
        router.get("/api/health").handler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("status", "healthy").encode());
        });

        return router;
    }

    private void handleRegister(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        String username = body.getString("username");
        String email = body.getString("email");
        String password = body.getString("password");

        if (username == null || email == null || password == null) {
            ctx.response().setStatusCode(400)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("success", false).put("message", "Missing fields").encode());
            return;
        }

        String passwordHash = hashPassword(password);

        client.preparedQuery("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)")
                .execute(Tuple.of(username, email, passwordHash))
                .onSuccess(result -> {
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", true).encode());
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", false).put("message", "Registration failed").encode());
                });
    }

    private void handleLogin(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        String username = body.getString("username");
        String password = body.getString("password");

        String passwordHash = hashPassword(password);

        client.preparedQuery("SELECT id, username FROM users WHERE username = ? AND password_hash = ?")
                .execute(Tuple.of(username, passwordHash))
                .onSuccess(result -> {
                    if (result.size() > 0) {
                        Row row = result.iterator().next();
                        ctx.response()
                                .putHeader("content-type", "application/json")
                                .end(new JsonObject()
                                        .put("success", true)
                                        .put("user_id", row.getInteger("id"))
                                        .put("username", row.getString("username"))
                                        .encode());
                    } else {
                        ctx.response().setStatusCode(401)
                                .putHeader("content-type", "application/json")
                                .end(new JsonObject().put("success", false).put("message", "Invalid credentials").encode());
                    }
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", false).put("message", "Login failed").encode());
                });
    }

    private void handleGetWeather(RoutingContext ctx) {
        String location = ctx.pathParam("location");

        // Check cache first
        client.preparedQuery("SELECT weather_data FROM weather_cache WHERE location = ? AND cached_at > DATE_SUB(NOW(), INTERVAL 10 MINUTE)")
                .execute(Tuple.of(location))
                .onSuccess(result -> {
                    if (result.size() > 0) {
                        // Return cached data
                        Row row = result.iterator().next();
                        ctx.response()
                                .putHeader("content-type", "application/json")
                                .end(row.getValue("weather_data").toString());
                    } else {
                        // Fetch fresh data from OpenWeatherMap
                        fetchWeatherFromAPI(location, ctx);
                    }
                })
                .onFailure(err -> fetchWeatherFromAPI(location, ctx));
    }

    private void fetchWeatherFromAPI(String location, RoutingContext ctx) {
        // First get coordinates
        webClient.getAbs(OPENWEATHER_BASE_URL + "/weather")
                .addQueryParam("q", location)
                .addQueryParam("appid", OPENWEATHER_API_KEY)
                .addQueryParam("units", "metric")
                .send()
                .onSuccess(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject weatherData = response.bodyAsJsonObject();
                        double lat = weatherData.getJsonObject("coord").getDouble("lat");
                        double lon = weatherData.getJsonObject("coord").getDouble("lon");

                        // Get detailed weather data including forecasts
                        webClient.getAbs(OPENWEATHER_BASE_URL + "/onecall")
                                .addQueryParam("lat", String.valueOf(lat))
                                .addQueryParam("lon", String.valueOf(lon))
                                .addQueryParam("appid", OPENWEATHER_API_KEY)
                                .addQueryParam("units", "metric")
                                .addQueryParam("exclude", "minutely")
                                .send()
                                .onSuccess(detailedResponse -> {
                                    if (detailedResponse.statusCode() == 200) {
                                        JsonObject detailedData = detailedResponse.bodyAsJsonObject();

                                        // Cache the data
                                        client.preparedQuery("INSERT INTO weather_cache (location, weather_data) VALUES (?, ?) ON DUPLICATE KEY UPDATE weather_data = ?, cached_at = NOW()")
                                                .execute(Tuple.of(location, detailedData.encode(), detailedData.encode()));

                                        // Check for triggered alerts
                                        checkWeatherAlerts(location, detailedData);

                                        ctx.response()
                                                .putHeader("content-type", "application/json")
                                                .end(detailedData.encode());
                                    } else {
                                        ctx.response().setStatusCode(500)
                                                .putHeader("content-type", "application/json")
                                                .end(new JsonObject().put("error", "Failed to fetch detailed weather data").encode());
                                    }
                                })
                                .onFailure(err -> {
                                    ctx.response().setStatusCode(500)
                                            .putHeader("content-type", "application/json")
                                            .end(new JsonObject().put("error", "Failed to fetch weather data").encode());
                                });
                    } else {
                        ctx.response().setStatusCode(404)
                                .putHeader("content-type", "application/json")
                                .end(new JsonObject().put("error", "Location not found").encode());
                    }
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("error", "Failed to fetch weather data").encode());
                });
    }

    private void handleSaveLocation(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        int userId = body.getInteger("user_id");
        String location = body.getString("location");
        String displayName = body.getString("display_name");

        // First check if user already has 5 locations
        client.preparedQuery("SELECT COUNT(*) as count FROM user_locations WHERE user_id = ?")
                .execute(Tuple.of(userId))
                .onSuccess(result -> {
                    Row row = result.iterator().next();
                    int count = row.getInteger("count");

                    if (count >= 5) {
                        ctx.response().setStatusCode(400)
                                .putHeader("content-type", "application/json")
                                .end(new JsonObject().put("success", false).put("message", "Maximum 5 locations allowed").encode());
                        return;
                    }

                    // Get coordinates for the location
                    webClient.getAbs(OPENWEATHER_BASE_URL + "/weather")
                            .addQueryParam("q", location)
                            .addQueryParam("appid", OPENWEATHER_API_KEY)
                            .send()
                            .onSuccess(response -> {
                                if (response.statusCode() == 200) {
                                    JsonObject weatherData = response.bodyAsJsonObject();
                                    double lat = weatherData.getJsonObject("coord").getDouble("lat");
                                    double lon = weatherData.getJsonObject("coord").getDouble("lon");

                                    client.preparedQuery("INSERT INTO user_locations (user_id, location, display_name, latitude, longitude) VALUES (?, ?, ?, ?, ?)")
                                            .execute(Tuple.of(userId, location, displayName, lat, lon))
                                            .onSuccess(insertResult -> {
                                                ctx.response()
                                                        .putHeader("content-type", "application/json")
                                                        .end(new JsonObject().put("success", true).encode());
                                            })
                                            .onFailure(err -> {
                                                ctx.response().setStatusCode(500)
                                                        .putHeader("content-type", "application/json")
                                                        .end(new JsonObject().put("success", false).put("message", "Failed to save location").encode());
                                            });
                                } else {
                                    ctx.response().setStatusCode(400)
                                            .putHeader("content-type", "application/json")
                                            .end(new JsonObject().put("success", false).put("message", "Invalid location").encode());
                                }
                            })
                            .onFailure(err -> {
                                ctx.response().setStatusCode(500)
                                        .putHeader("content-type", "application/json")
                                        .end(new JsonObject().put("success", false).put("message", "Failed to validate location").encode());
                            });
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", false).put("message", "Database error").encode());
                });
    }

    private void handleGetUserLocations(RoutingContext ctx) {
        String userIdStr = ctx.pathParam("userId");
        int userId = Integer.parseInt(userIdStr);

        client.preparedQuery("SELECT id, location, display_name, latitude, longitude FROM user_locations WHERE user_id = ? ORDER BY created_at DESC")
                .execute(Tuple.of(userId))
                .onSuccess(result -> {
                    JsonArray locations = new JsonArray();
                    for (Row row : result) {
                        JsonObject location = new JsonObject()
                                .put("id", row.getInteger("id"))
                                .put("location", row.getString("location"))
                                .put("display_name", row.getString("display_name"))
                                .put("latitude", row.getValue("latitude"))
                                .put("longitude", row.getValue("longitude"));
                        locations.add(location);
                    }

                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("locations", locations).encode());
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("error", "Failed to fetch locations").encode());
                });
    }

    private void handleDeleteLocation(RoutingContext ctx) {
        String locationIdStr = ctx.pathParam("locationId");
        int locationId = Integer.parseInt(locationIdStr);

        client.preparedQuery("DELETE FROM user_locations WHERE id = ?")
                .execute(Tuple.of(locationId))
                .onSuccess(result -> {
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", true).encode());
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", false).put("message", "Failed to delete location").encode());
                });
    }

    private void handleCreateAlert(RoutingContext ctx) {
        JsonObject body = ctx.getBodyAsJson();
        int userId = body.getInteger("user_id");
        String location = body.getString("location");
        String alertType = body.getString("alert_type");
        String condition = body.getString("condition");
        double threshold = body.getDouble("threshold");

        client.preparedQuery("INSERT INTO weather_alerts (user_id, location, alert_type, condition_type, threshold_value) VALUES (?, ?, ?, ?, ?)")
                .execute(Tuple.of(userId, location, alertType, condition, threshold))
                .onSuccess(result -> {
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", true).encode());
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", false).put("message", "Failed to create alert").encode());
                });
    }

    private void handleGetUserAlerts(RoutingContext ctx) {
        String userIdStr = ctx.pathParam("userId");
        int userId = Integer.parseInt(userIdStr);

        client.preparedQuery("SELECT id, location, alert_type, condition_type, threshold_value, is_active, last_triggered FROM weather_alerts WHERE user_id = ? AND is_active = TRUE ORDER BY created_at DESC")
                .execute(Tuple.of(userId))
                .onSuccess(result -> {
                    JsonArray alerts = new JsonArray();
                    for (Row row : result) {
                        JsonObject alert = new JsonObject()
                                .put("id", row.getInteger("id"))
                                .put("location", row.getString("location"))
                                .put("alert_type", row.getString("alert_type"))
                                .put("condition", row.getString("condition_type"))
                                .put("threshold", row.getValue("threshold_value"))
                                .put("triggered", row.getValue("last_triggered") != null);
                        alerts.add(alert);
                    }

                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("alerts", alerts).encode());
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("error", "Failed to fetch alerts").encode());
                });
    }

    private void handleDeleteAlert(RoutingContext ctx) {
        String alertIdStr = ctx.pathParam("alertId");
        int alertId = Integer.parseInt(alertIdStr);

        client.preparedQuery("UPDATE weather_alerts SET is_active = FALSE WHERE id = ?")
                .execute(Tuple.of(alertId))
                .onSuccess(result -> {
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", true).encode());
                })
                .onFailure(err -> {
                    ctx.response().setStatusCode(500)
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("success", false).put("message", "Failed to delete alert").encode());
                });
    }

    private void checkWeatherAlerts(String location, JsonObject weatherData) {
        client.preparedQuery("SELECT id, user_id, alert_type, condition_type, threshold_value FROM weather_alerts WHERE location = ? AND is_active = TRUE")
                .execute(Tuple.of(location))
                .onSuccess(result -> {
                    JsonObject current = weatherData.getJsonObject("current");

                    for (Row row : result) {
                        int alertId = row.getInteger("id");
                        String alertType = row.getString("alert_type");
                        String condition = row.getString("condition_type");
                        double threshold = row.getDouble("threshold_value");

                        boolean shouldTrigger = false;
                        double currentValue = 0;

                        switch (alertType.toLowerCase()) {
                            case "temperature":
                                currentValue = current.getDouble("temp");
                                break;
                            case "humidity":
                                currentValue = current.getDouble("humidity");
                                break;
                            case "wind":
                                currentValue = current.getDouble("wind_speed");
                                break;
                            case "rain":
                                JsonObject rain = current.getJsonObject("rain");
                                currentValue = rain != null ? rain.getDouble("1h", 0.0) : 0.0;
                                break;
                        }

                        if (condition.equals("above")) {
                            shouldTrigger = currentValue > threshold;
                        } else if (condition.equals("below")) {
                            shouldTrigger = currentValue < threshold;
                        }

                        if (shouldTrigger) {
                            client.preparedQuery("UPDATE weather_alerts SET last_triggered = NOW() WHERE id = ?")
                                    .execute(Tuple.of(alertId));

                            // Here you could add notification logic (email, push notifications, etc.)
                            System.out.println("Alert triggered for alert ID: " + alertId +
                                    " - " + alertType + " " + condition + " " + threshold +
                                    " (current: " + currentValue + ")");
                        }
                    }
                });
    }

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new App())
                .onSuccess(id -> System.out.println("WeatherPro Backend deployed successfully"))
                .onFailure(err -> {
                    System.err.println("Failed to deploy WeatherPro Backend: " + err.getMessage());
                    err.printStackTrace();
                });
    }
}