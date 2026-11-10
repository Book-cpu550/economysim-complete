package com.booke.nim;

import com.example.economyadvisor.AdvisorConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;

/** In-process transport for the four released AI modules; no localhost service. */
public final class NimHttp {
    static final URI ENDPOINT = URI.create("https://integrate.api.nvidia.com/v1/chat/completions");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    private NimHttp() { }

    public static String model() { return AdvisorConfig.get().model; }
    public static String baseUri() { return "https://integrate.api.nvidia.com"; }

    public static HttpResponse<String> send(HttpClient ignoredClient, HttpRequest source,
            HttpResponse.BodyHandler<String> ignoredHandler) throws IOException, InterruptedException {
        return sendTo(HTTP, ENDPOINT, source);
    }

    public static CompletableFuture<HttpResponse<String>> sendAsync(HttpClient ignoredClient,
            HttpRequest source, HttpResponse.BodyHandler<String> ignoredHandler) {
        return sendAsyncTo(HTTP, ENDPOINT, source);
    }

    // Package-private seams let tests exercise real HTTP without external API calls.
    static HttpResponse<String> sendTo(HttpClient client, URI endpoint, HttpRequest source)
            throws InterruptedException {
        Prepared prepared;
        try {
            prepared = prepare(source, endpoint);
        } catch (Exception e) {
            return error(source, 400, safePreparationError(e));
        }
        try {
            return adapt(client.send(prepared.request(), HttpResponse.BodyHandlers.ofString()), prepared);
        } catch (IOException e) {
            return networkError(prepared.request(), e);
        }
    }

    static CompletableFuture<HttpResponse<String>> sendAsyncTo(HttpClient client, URI endpoint,
            HttpRequest source) {
        Prepared prepared;
        try {
            prepared = prepare(source, endpoint);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(error(source, 400, safePreparationError(e)));
        }
        CompletableFuture<HttpResponse<String>> upstream = client.sendAsync(
                prepared.request(), HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                boolean cancelled = super.cancel(interrupt);
                if (cancelled) upstream.cancel(interrupt);
                return cancelled;
            }
        };
        upstream.whenComplete((response, failure) -> {
            if (failure != null) result.complete(networkError(prepared.request(), failure));
            else result.complete(adapt(response, prepared));
        });
        return result;
    }

    static Prepared prepare(HttpRequest source, URI endpoint) throws Exception {
        AdvisorConfig config = AdvisorConfig.get();
        String key = config.resolveKey();
        String model = config.model == null ? "" : config.model.trim();
        if (key.isBlank()) throw new SetupException("Set your NVIDIA API key with /advisor key paste.");
        if (model.isBlank()) throw new SetupException("Set a NVIDIA model ID with /advisor model <model-id>.");
        if (config.timeoutSeconds < 1 || config.timeoutSeconds > 300)
            throw new SetupException("Set timeoutSeconds to 1–300 in economysim-advisor.json.");
        JsonObject original = JsonParser.parseString(readBody(source)).getAsJsonObject();
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        boolean advisor = source.uri().getPath().endsWith("/v1/messages");
        if (original.has("system")) addSystem(messages, original.get("system").getAsString());
        boolean structured = original.has("format") && !original.get("format").isJsonNull();
        if (structured) {
            addSystem(messages, "Return only a JSON object matching this schema: " + original.get("format"));
            if (config.jsonMode) {
                JsonObject format = new JsonObject();
                format.addProperty("type", "json_object");
                payload.add("response_format", format);
            }
        }
        for (JsonElement message : original.getAsJsonArray("messages")) messages.add(message.deepCopy());
        payload.add("messages", messages);
        JsonObject options = original.has("options") ? original.getAsJsonObject("options") : new JsonObject();
        JsonElement tokens = original.has("max_tokens") ? original.get("max_tokens") : options.get("num_predict");
        int maxTokens = tokens == null ? config.maxTokens : tokens.getAsInt();
        if (maxTokens < 1) throw new SetupException("maxTokens must be positive in economysim-advisor.json.");
        payload.addProperty("max_tokens", maxTokens);
        if (options.has("temperature")) payload.add("temperature", options.get("temperature").deepCopy());
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(config.timeoutSeconds))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build();
        return new Prepared(request, advisor, structured);
    }

    private static void addSystem(JsonArray messages, String text) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "system");
        message.addProperty("content", text);
        messages.add(message);
    }

    private static String readBody(HttpRequest source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> body = new CompletableFuture<>();
        source.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { body.completeExceptionally(error); }
            @Override public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return body.get(5, TimeUnit.SECONDS);
    }

    private static HttpResponse<String> adapt(HttpResponse<String> response, Prepared prepared) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String message = switch (status) {
                case 401, 403 -> "NVIDIA rejected the API key or model access; check /advisor key and model access.";
                case 404 -> "NVIDIA model or endpoint not found; check /advisor model <model-id>.";
                case 429 -> "NVIDIA rate limit reached; wait before trying again.";
                case 400, 422 -> "NVIDIA rejected the model or request; verify its chat/JSON support (jsonMode can be disabled).";
                default -> "NVIDIA request failed (HTTP " + status + "); try again later.";
            };
            return error(response.request(), status, message);
        }
        try {
            JsonObject choice = JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonElement value = choice.getAsJsonObject("message").get("content");
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
                return error(response.request(), 502, "NVIDIA returned no final text; check the model and token limit.");
            String content = value.getAsString().trim();
            if (prepared.structured()) {
                if (choice.has("finish_reason") && "length".equals(choice.get("finish_reason").getAsString()))
                    return error(response.request(), 502, "NVIDIA reached the output token limit before completing JSON.");
                if (!JsonParser.parseString(content).isJsonObject())
                    return error(response.request(), 502, "NVIDIA returned invalid structured output; select a JSON-capable model.");
            }
            // Preserve each released module's parser and domain validation.
            JsonObject envelope = new JsonObject();
            if (prepared.advisor()) {
                JsonObject block = new JsonObject();
                block.addProperty("type", "text");
                block.addProperty("text", content);
                JsonArray blocks = new JsonArray();
                blocks.add(block);
                envelope.add("content", blocks);
            } else {
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                message.addProperty("content", content);
                envelope.add("message", message);
            }
            return new Reply(200, envelope.toString(), response.request());
        } catch (RuntimeException e) {
            return error(response.request(), 502, "NVIDIA returned an unreadable response or invalid JSON output.");
        }
    }

    private static HttpResponse<String> networkError(HttpRequest request, Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof HttpTimeoutException)
            return error(request, 504, "NVIDIA request timed out; retry or increase timeoutSeconds (maximum 300).");
        return error(request, 503, "Cannot reach NVIDIA NIM; check your internet connection and try again.");
    }

    private static String safePreparationError(Exception e) {
        return e instanceof SetupException ? e.getMessage() : "Could not prepare the NVIDIA request; check your NIM configuration.";
    }

    private static HttpResponse<String> error(HttpRequest request, int status, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return new Reply(status, body.toString(), request);
    }

    record Prepared(HttpRequest request, boolean advisor, boolean structured) { }
    private static final class SetupException extends Exception {
        SetupException(String message) { super(message); }
    }
    private record Reply(int statusCode, String body, HttpRequest request) implements HttpResponse<String> {
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
