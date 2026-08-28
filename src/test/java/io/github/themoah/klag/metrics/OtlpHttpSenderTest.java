package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.ipc.http.HttpSender;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the JDK-{@code HttpClient}-backed {@link HttpSender} that {@link OtlpTls#httpSender}
 * returns. Uses an in-process {@link HttpServer}: plain HTTP is enough to exercise the sender's new
 * risk surface — header forwarding, redirect following, and status/body response mapping. The
 * {@link SSLContext} wiring is covered by the additive-trust unit tests in {@link OtlpTlsTest}.
 */
class OtlpHttpSenderTest {

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private HttpSender sender() throws Exception {
    return OtlpTls.httpSender(SSLContext.getDefault(), Duration.ofSeconds(2), Duration.ofSeconds(5));
  }

  private static void respond(HttpExchange ex, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  @Test
  void forwardsHeadersAndBody_andMapsSuccessResponse() throws Throwable {
    AtomicReference<String> seenHeader = new AtomicReference<>();
    AtomicReference<String> seenContentType = new AtomicReference<>();
    AtomicReference<Integer> seenBodyLen = new AtomicReference<>();
    server.createContext("/v1/metrics", ex -> {
      seenHeader.set(ex.getRequestHeaders().getFirst("X-Klag-Test"));
      seenContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
      seenBodyLen.set(ex.getRequestBody().readAllBytes().length);
      respond(ex, 200, "ok");
    });

    HttpSender.Response resp = sender().post(baseUrl + "/v1/metrics")
        .withHeader("X-Klag-Test", "klag")
        .withContent("application/x-protobuf", new byte[] {1, 2, 3, 4})
        .send();

    assertEquals(200, resp.code());
    assertEquals("ok", resp.body());
    assertEquals("klag", seenHeader.get(), "custom request header was not forwarded");
    assertEquals("application/x-protobuf", seenContentType.get(), "content type was not forwarded");
    assertEquals(4, seenBodyLen.get(), "request body was not forwarded intact");
  }

  @Test
  void followsRedirect() throws Throwable {
    server.createContext("/redirect", ex -> {
      ex.getResponseHeaders().set("Location", baseUrl + "/final");
      ex.sendResponseHeaders(302, -1);
      ex.close();
    });
    server.createContext("/final", ex -> respond(ex, 200, "landed"));

    HttpSender.Response resp = sender().post(baseUrl + "/redirect")
        .withContent("application/x-protobuf", new byte[] {1})
        .send();

    assertEquals(200, resp.code(), "sender should follow the 302 to the final endpoint");
    assertEquals("landed", resp.body());
  }

  @Test
  void mapsErrorResponse() throws Throwable {
    server.createContext("/err", ex -> respond(ex, 503, "busy"));

    HttpSender.Response resp = sender().post(baseUrl + "/err")
        .withContent("application/x-protobuf", new byte[] {1})
        .send();

    assertEquals(503, resp.code());
    assertEquals("busy", resp.body());
    assertFalse(resp.isSuccessful(), "5xx should not map to a successful response");
  }
}
