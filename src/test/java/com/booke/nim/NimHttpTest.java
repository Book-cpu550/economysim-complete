package com.booke.nim;

import com.example.economyadvisor.AdvisorConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone contract tests: actual HTTP, no Minecraft or real API credentials. */
public final class NimHttpTest {
    private static final String KEY = "nvapi-test-only-do-not-use-123456";
    private static final String OLLAMA = """
        {"model":"gemma4:12b","stream":false,"think":false,"keep_alive":"5m",
         "options":{"temperature":0.2,"num_predict":800,"num_ctx":8192},
         "messages":[{"role":"system","content":"Keep the facts"},
                     {"role":"user","content":"question"},
                     {"role":"assistant","content":"prior reply"}]}
        """;
    private static final String ADVISOR = """
        {"model":"old-model","system":"Advisor rules","max_tokens":900,
         "thinking":{"type":"disabled"},
         "messages":[{"role":"user","content":"market snapshot"}]}
        """;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path configDir = Path.of(System.getProperty("economysim.test.config"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("economysim-advisor.json"),
                "{\"model\":\"gemma4:12b\",\"apiKey\":\"ollama-local-placeholder\",\"historyTurns\":7}");
        AdvisorConfig config = AdvisorConfig.get();
        check(config.model.isEmpty() && config.resolveKey().isEmpty(), "legacy settings require NIM setup");
        check(config.historyTurns == 7, "existing preferences survive migration");
        check(config.keySource().equals("none"), "missing key status");
        config.setApiKey(KEY);
        config.model = "test/instruct-model";
        config.save();
        check(config.keySource().equals("config file"), "key command interface remains compatible");
        check(Files.readString(configDir.resolve("economysim-advisor.json")).contains(KEY), "key saved locally");
        check(NimHttp.model().equals(config.model), "model UI reads shared config");
        check(NimHttp.ENDPOINT.toString().equals("https://integrate.api.nvidia.com/v1/chat/completions"), "production endpoint");

        var executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        AtomicReference<String> receivedLegacyKey = new AtomicReference<>();
        AtomicReference<String> receivedLegacyVersion = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(200);
        AtomicInteger delay = new AtomicInteger();
        AtomicReference<String> reply = new AtomicReference<>(completion("hello"));
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedLegacyKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            receivedLegacyVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            try {
                Thread.sleep(delay.get());
                byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) {
                // Cancellation and timeout intentionally close a request early.
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest source = source("/api/chat", OLLAMA);
            HttpResponse<String> result = NimHttp.sendTo(client, endpoint, source);
            check(result.statusCode() == 200 && content(result).equals("hello"), "Ollama response contract");
            check(receivedAuth.get().equals("Bearer " + KEY), "bearer auth");
            check(receivedLegacyKey.get() == null && receivedLegacyVersion.get() == null, "legacy auth headers not forwarded");
            JsonObject sent = JsonParser.parseString(receivedBody.get()).getAsJsonObject();
            check(sent.get("model").getAsString().equals(config.model), "shared model overrides old model");
            check(sent.getAsJsonArray("messages").size() == 3, "history and system prompt preserved");
            check(sent.get("max_tokens").getAsInt() == 800, "num_predict converted");
            check(sent.get("temperature").getAsDouble() == 0.2, "temperature preserved");
            check(!sent.has("options") && !sent.has("keep_alive") && !sent.has("think"), "Ollama-only fields removed");
            check(!sent.get("stream").getAsBoolean(), "non-streaming");
            check(!receivedBody.get().contains(KEY), "credential absent from payload");

            result = NimHttp.sendAsyncTo(client, endpoint, source("/v1/messages", ADVISOR)).get(5, TimeUnit.SECONDS);
            JsonObject advisorReply = JsonParser.parseString(result.body()).getAsJsonObject();
            check(advisorReply.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().equals("hello"), "Advisor response contract");
            sent = JsonParser.parseString(receivedBody.get()).getAsJsonObject();
            check(sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString().equals("system"), "Advisor system prompt converted");
            check(sent.get("max_tokens").getAsInt() == 900 && !sent.has("thinking") && !sent.has("system"), "Advisor fields converted");

            for (String structuredReply : new String[]{"{\"summary\":\"Good\",\"grades\":{\"damage\":\"A\"}}", "{\"price\":1234}"}) {
                JsonObject structured = JsonParser.parseString(OLLAMA).getAsJsonObject();
                structured.add("format", JsonParser.parseString("{\"type\":\"object\"}"));
                reply.set(completion(structuredReply));
                result = NimHttp.sendTo(client, endpoint, source("/api/chat", structured.toString()));
                check(content(result).equals(structuredReply), "Item Rater / Predicted Prices JSON preserved");
                sent = JsonParser.parseString(receivedBody.get()).getAsJsonObject();
                check(sent.getAsJsonObject("response_format").get("type").getAsString().equals("json_object"), "JSON response mode");
                check(sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("schema"), "schema instruction preserved");
            }
            JsonObject structured = JsonParser.parseString(OLLAMA).getAsJsonObject();
            structured.addProperty("format", "json");
            config.jsonMode = false;
            NimHttp.sendTo(client, endpoint, source("/api/chat", structured.toString()));
            check(!JsonParser.parseString(receivedBody.get()).getAsJsonObject().has("response_format"), "prompt-only models supported");
            config.jsonMode = true;
            reply.set(completion("not JSON"));
            result = NimHttp.sendTo(client, endpoint, source("/api/chat", structured.toString()));
            check(result.statusCode() == 502 && result.body().contains("invalid JSON"), "invalid structured output rejected");

            for (int code : new int[]{400, 401, 403, 404, 422, 429, 500, 503}) {
                status.set(code);
                reply.set("upstream error including a credential: " + KEY);
                int before = hits.get();
                result = NimHttp.sendTo(client, endpoint, source);
                check(result.statusCode() == code && !result.body().contains(KEY), "safe HTTP " + code);
                check(hits.get() == before + 1, "no automatic retry for HTTP " + code);
            }
            status.set(200);
            for (String invalid : new String[]{"not json", "{}", "{\"choices\":[]}", completion(""), "{\"choices\":[{\"message\":{\"content\":null}}]}"}) {
                reply.set(invalid);
                result = NimHttp.sendAsyncTo(client, endpoint, source).get(5, TimeUnit.SECONDS);
                check(result.statusCode() == 502, "malformed or empty completion rejected");
            }
            int before = hits.get();
            config.apiKey = "";
            result = NimHttp.sendTo(client, endpoint, source);
            check(result.statusCode() == 400 && result.body().contains("/advisor key paste"), "missing key action");
            config.apiKey = KEY;
            config.model = "";
            result = NimHttp.sendAsyncTo(client, endpoint, source).get();
            check(result.body().contains("/advisor model"), "missing model action");
            config.model = "test/changed-model";
            config.timeoutSeconds = 0;
            result = NimHttp.sendTo(client, endpoint, source);
            check(result.statusCode() == 400 && hits.get() == before, "bad config never reaches network");
            config.timeoutSeconds = 1;
            check(NimHttp.prepare(source, endpoint).request().timeout().orElseThrow().toSeconds() == 1, "shared timeout applied");
            reply.set(completion("late response"));
            delay.set(1800);
            result = NimHttp.sendTo(client, endpoint, source);
            check(result.statusCode() == 504 && result.body().contains("timed out"), "synchronous timeout");
            result = NimHttp.sendAsyncTo(client, endpoint, source).get(5, TimeUnit.SECONDS);
            check(result.statusCode() == 504, "asynchronous timeout");
            config.timeoutSeconds = 120;
            var pending = NimHttp.sendAsyncTo(client, endpoint, source);
            check(pending.cancel(true) && pending.isCancelled(), "loan cancellation propagates");
            delay.set(0);
            result = NimHttp.sendTo(client, endpoint, source);
            check(result.statusCode() == 200 && receivedBody.get().contains("test/changed-model"), "changed model used without restart");
            config.setApiKey("");
            check(config.resolveKey().isBlank(), "clear key command interface");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
        System.out.println("PASS: " + assertions + " transport/config assertions.");
    }

    private static HttpRequest source(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:11434" + path))
                .header("x-api-key", "legacy-key").header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static String completion(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        return "{\"choices\":[{\"message\":" + message + "}]}";
    }

    private static String content(HttpResponse<String> reply) {
        return JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        assertions++;
    }
}
