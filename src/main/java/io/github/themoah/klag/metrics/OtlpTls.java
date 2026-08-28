package io.github.themoah.klag.metrics;

import io.micrometer.core.ipc.http.HttpSender;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TLS trust configuration for the OTLP metrics exporter.
 *
 * <p>Micrometer's {@code OtlpMeterRegistry} sends over HTTPS using the JVM default
 * {@link SSLContext}, so a collector served with an internally-signed certificate
 * (e.g. an in-cluster OpenTelemetry Collector) fails PKIX validation. When
 * {@code OTLP_CA_CERT_PATH} (or the OTLP-spec {@code OTEL_EXPORTER_OTLP_CERTIFICATE})
 * points at a PEM bundle, this class builds an {@link SSLContext} that trusts the
 * JDK's default CAs <em>plus</em> the extra certificates, and exposes an
 * {@link HttpSender} that uses it. The trust is <strong>additive</strong> and scoped
 * to the OTLP exporter — it never mutates JVM-global state and can never reduce the
 * trust the JVM already has.
 *
 * <p>The Kafka client is unaffected: kafka-clients builds its own {@code SSLContext}
 * from {@code ssl.truststore.location} and does not consult this one.
 */
public final class OtlpTls {

  private static final Logger log = LoggerFactory.getLogger(OtlpTls.class);

  private OtlpTls() {}

  /**
   * Reads the configured CA-cert path from the environment and builds an additive-trust
   * {@link SSLContext}. Custom {@code OTLP_CA_CERT_PATH} takes precedence over the
   * OTLP-spec {@code OTEL_EXPORTER_OTLP_CERTIFICATE}.
   *
   * @return the additive-trust context, or empty when no CA-cert path is configured
   * @throws IllegalStateException when a path is configured but yields no usable certificates
   *     (missing, empty, or unparseable file). Failing here is deliberate: silently falling
   *     back to JVM default trust would reproduce the exact PKIX failure this setting exists to
   *     fix, so a typo or unmounted secret would be indistinguishable from the original error.
   */
  public static Optional<SSLContext> sslContextFromEnvironment() {
    String path = configuredCertPath();
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    try {
      SSLContext ctx = buildAdditiveContext(Path.of(path));
      log.info("OTLP exporter will trust extra CA certificates from {}", path);
      return Optional.of(ctx);
    } catch (Exception e) {
      throw new IllegalStateException(
          "OTLP CA cert path '" + path + "' is set but no usable certificates could be loaded "
          + "(missing, empty, or unparseable PEM); refusing to fall back to JVM default trust",
          e);
    }
  }

  /** Resolves the configured CA-cert path, custom var first, then the OTLP-spec var. */
  static String configuredCertPath() {
    String path = System.getenv("OTLP_CA_CERT_PATH");
    if (path == null || path.isBlank()) {
      path = System.getenv("OTEL_EXPORTER_OTLP_CERTIFICATE");
    }
    return path;
  }

  /**
   * Builds an {@link SSLContext} whose trust manager combines the JDK default CAs with
   * the X.509 certificates in {@code pemPath}. A bundle that yields no certificates (empty
   * or cert-less file) raises a {@link java.security.cert.CertificateException}, as does
   * invalid content — so the caller falls back to the stock JVM-default path rather than
   * reporting custom trust it does not have.
   */
  static SSLContext buildAdditiveContext(Path pemPath) throws Exception {
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, new TrustManager[] {buildAdditiveTrustManager(pemPath)}, null);
    return ctx;
  }

  /**
   * Builds the additive trust manager that backs {@link #buildAdditiveContext}: the JDK
   * default trust anchors merged with the X.509 certificates in {@code pemPath} into a single
   * PKIX trust manager. Its accepted issuers are always a superset of the JDK defaults, so trust
   * is only ever widened. A bundle with no certificates raises a
   * {@link java.security.cert.CertificateException} rather than silently yielding a defaults-only
   * manager, so a misconfigured path is never mistaken for added trust. Package-private for tests.
   */
  static X509TrustManager buildAdditiveTrustManager(Path pemPath) throws Exception {
    List<X509Certificate> extras = new ArrayList<>();
    if (Files.size(pemPath) > 0) {
      try (InputStream in = Files.newInputStream(pemPath)) {
        for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
          extras.add((X509Certificate) cert);
        }
      }
    }

    if (extras.isEmpty()) {
      throw new java.security.cert.CertificateException(
          "OTLP CA cert file " + pemPath + " contained no certificates");
    }

    // Merge the JDK default trust anchors and the extra CAs into one KeyStore, then build a single
    // PKIX trust manager over it. That yields a real X509ExtendedTrustManager (endpoint identity
    // checks intact, no JSSE wrapper), and its trust anchors are a superset of the JDK defaults.
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    ks.load(null, null);
    int i = 0;
    for (X509Certificate ca : defaultTrustManager().getAcceptedIssuers()) {
      ks.setCertificateEntry("default-ca-" + (i++), ca);
    }
    for (X509Certificate ca : extras) {
      ks.setCertificateEntry("otlp-ca-" + (i++), ca);
    }
    return firstX509(loadTrustManagerFactory(ks));
  }

  /** Returns an {@link HttpSender} backed by the JDK HttpClient using {@code sslContext}. */
  public static HttpSender httpSender(SSLContext sslContext, Duration connectTimeout,
      Duration readTimeout) {
    return new JdkHttpSender(sslContext, connectTimeout, readTimeout);
  }

  private static X509TrustManager defaultTrustManager() throws Exception {
    return firstX509(loadTrustManagerFactory((KeyStore) null));
  }

  private static TrustManagerFactory loadTrustManagerFactory(KeyStore ks) throws Exception {
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ks);
    return tmf;
  }

  private static X509TrustManager firstX509(TrustManagerFactory tmf) {
    for (TrustManager tm : tmf.getTrustManagers()) {
      if (tm instanceof X509TrustManager x509) {
        return x509;
      }
    }
    throw new IllegalStateException("No X509TrustManager found");
  }

  /**
   * Minimal {@link HttpSender} over {@link HttpClient}. Micrometer's stock
   * {@code HttpUrlConnectionSender} has no per-connection SSL hook, so a custom sender is
   * the only way to scope a non-default {@link SSLContext} to the OTLP exporter.
   */
  private static final class JdkHttpSender implements HttpSender {
    private final HttpClient client;
    private final Duration readTimeout;

    JdkHttpSender(SSLContext sslContext, Duration connectTimeout, Duration readTimeout) {
      this.client = HttpClient.newBuilder()
          .sslContext(sslContext)
          .connectTimeout(connectTimeout)
          // Match Micrometer's stock HttpUrlConnectionSender, which follows same-protocol
          // redirects; HttpClient otherwise defaults to Redirect.NEVER.
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();
      this.readTimeout = readTimeout;
    }

    @Override
    public Response send(Request request) throws Throwable {
      byte[] entity = request.getEntity();
      HttpRequest.BodyPublisher body = (entity != null && entity.length > 0)
          ? HttpRequest.BodyPublishers.ofByteArray(entity)
          : HttpRequest.BodyPublishers.noBody();

      HttpRequest.Builder builder = HttpRequest.newBuilder(request.getUrl().toURI())
          .timeout(readTimeout)
          .method(request.getMethod().name(), body);

      for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
        try {
          builder.header(header.getKey(), header.getValue());
        } catch (IllegalArgumentException restricted) {
          // HttpClient forbids setting a few headers (e.g. Content-Length); it sets them itself.
          log.debug("Skipping restricted request header {}", header.getKey());
        }
      }

      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    }
  }
}
