import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class SimpleLoadBalancer {
    // 1. Register targets (backend servers)
    private static final List<String> BACKEND_SERVERS = List.of(
            "http://localhost:8081",
            "http://localhost:8082",
            "http://localhost:8083",
            "http://localhost:8085" // ❌ This one will fail!
    );

    // Track health status: Key = URL, Value = isHealthy
    private static final Map<String, Boolean> serverHealthMap = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        // Step 1. Spin up fake backend servers so we have something to test against
        startMockBackend(8081, "Server A");
        startMockBackend(8082, "Server B");
        startMockBackend(8083, "Server C");

        // Step 2. Start health checks
        startHealthChecks();

        // Step 3. Start the Load Balancer on Port 8080
        HttpServer loadBalancer = HttpServer.create(new InetSocketAddress(8080), 0);
        loadBalancer.createContext("/", new LoadBalancerHandler());

        loadBalancer.setExecutor(Executors.newCachedThreadPool()); // Handle concurrency
        loadBalancer.start();

        System.out.println("Load balancer running on http://localhost:8080");
    }

    // Health check logic
    private static void startHealthChecks() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        HttpClient healthClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .build();

        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("Performing health checks...");
            for (String serverUrl : BACKEND_SERVERS) {
                try {
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(serverUrl))
                            .GET().build();

                    HttpResponse<Void> response = healthClient.send(request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) {
                        serverHealthMap.put(serverUrl, true);
                        System.out.println("Server " + serverUrl + " is healthy");
                    } else {
                        serverHealthMap.put(serverUrl, false);
                        System.out.println("Server " + serverUrl + " is unhealthy");
                    }
                } catch (Exception e) {
                    serverHealthMap.put(serverUrl, false);
                    System.out.println("Failed to perform health check for " + serverUrl);
                }
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    static class LoadBalancerHandler implements HttpHandler {
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                // A. Filter only HEALTHY servers
                List<String> healthyServers = BACKEND_SERVERS.stream()
                        .filter(server -> serverHealthMap.getOrDefault(server, false))
                        .collect(Collectors.toList());

                if (healthyServers.isEmpty()) {
                    String error = "503 Service Unavailable - No healthy backends!";
                    exchange.sendResponseHeaders(503, error.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(error.getBytes());
                    }
                    return;
                }

                // B. Selection strategy (Round robin)
                // We use modulo to cycle through the list of servers: 0, 1, 2, 0, 1, 2, ...
                int index = Math.abs(counter.getAndIncrement() % healthyServers.size());
                String targetUrl = healthyServers.get(index);

                System.out.println("Received request -> Routing to: " + targetUrl);

                // C. Forward the request to the selected server
                HttpRequest backendRequest = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .GET()
                        .build();

                // D.Send Request to the selected server
                HttpResponse<String> backendResponse = httpClient.send(backendRequest,
                        HttpResponse.BodyHandlers.ofString());

                // E. Send the response back to the client
                String responseBody = backendResponse.body();
                exchange.sendResponseHeaders(backendResponse.statusCode(), responseBody.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBody.getBytes());
                }

            } catch (Exception e) {
                e.printStackTrace();
                String error = "500 Internal Server Error";
                exchange.sendResponseHeaders(500, error.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes());
                }
            }
        }
    }

    // Helper to simulate backend servers
    private static void startMockBackend(int port, String serverName) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            String response = "Hello from " + serverName + " (Port " + port + ")";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.start();
        System.out.println("Backend server running on http://localhost:" + port);
    }
}