package com.example.aimindmap.integration;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阶段七-1：集成测试用最小 SSE 客户端（JDK HttpClient，无新增依赖）。
 * 后台线程逐行解析 text/event-stream，按空行切分事件；终态 complete 关闭流时到达 EOF。
 */
final class SseTestClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SseTestClient.class);

    record SseEvent(String name, String data) {
    }

    private final List<SseEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch endLatch = new CountDownLatch(1);
    private Thread readerThread;
    private final HttpResponse<InputStream> response;

    private SseTestClient(HttpResponse<InputStream> response) {
        this.response = response;
    }

    static SseTestClient open(int port, String externalJobId, String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String url = "http://127.0.0.1:" + port + "/api/sync-jobs/" + externalJobId + "/events?access_token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("SSE 订阅失败 status=" + response.statusCode() + " body=" + body);
        }
        SseTestClient sseClient = new SseTestClient(response);
        Thread reader = new Thread(sseClient::readLoop, "sse-test-reader-" + port);
        reader.setDaemon(true);
        reader.start();
        sseClient.readerThread = reader;
        return sseClient;
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventName != null) {
                        events.add(new SseEvent(eventName, data.toString()));
                    }
                    eventName = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()));
                }
            }
        } catch (Exception e) {
            log.debug("SSE 测试客户端读取结束：{}", e.toString());
        } finally {
            endLatch.countDown();
        }
    }

    List<SseEvent> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    long count(String eventName) {
        return events().stream().filter(event -> event.name().equals(eventName)).count();
    }

    boolean contains(String eventName) {
        return count(eventName) > 0;
    }

    boolean awaitEnd(Duration timeout) throws InterruptedException {
        return endLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        try {
            response.body().close();
        } catch (Exception ignored) {
            // 测试结束清理
        }
    }
}
