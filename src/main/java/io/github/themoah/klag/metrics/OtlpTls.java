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
   * @return the context, or empty when no path is configured or it cannot be loaded
   *     (in which case the exporter falls back to the JVM default trust)
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
      // Fail safe: keep the JVM default trust rather than breaking metrics startup.
      log.error("Failed to load OTLP CA certificates from {} - falling back to JVM default "
          + "trust; OTLP export to an internally-signed endpoint may fail", path, e);
      return Optional.empty();
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
   * the X.509 certificates in {@code pemPath}. An empty file yields a defaults-only
   * context; invalid content raises the underlying parse exception.
   */
  static SSLContext buildAdditiveContext(Path pemPath) throws Exception {
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, new TrustManager[] {buildAdditiveTrustManager(pemPath)}, null);
    return ctx;
  }

  /**
   * Builds the additive trust manager that backs {@link #buildAdditiveContext}: the JDK
   * default CAs combined with the X.509 certificates in {@code pemPath}. Its accepted
   * issuers are always a superset of the JDK defaults. Package-private for tests.
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

    List<X509TrustManager> managers = new ArrayList<>();
    managers.add(defaultTrustManager());
    if (extras.isEmpty()) {
      log.warn("OTLP CA cert file {} contained no certificates; using JVM default trust only",
          pemPath);
    } else {
      managers.add(trustManagerFor(extras));
    }
    return new CompositeX509TrustManager(managers);
  }

  /** Returns an {@link HttpSender} backed by the JDK HttpClient using {@code sslContext}. */
  public static HttpSender httpSender(SSLContext sslContext, Duration connectTimeout,
      Duration readTimeout) {
    return new JdkHttpSender(sslContext, connectTimeout, readTimeout);
  }

  private static X509TrustManager defaultTrustManager() throws Exception {
    return firstX509(loadTrustManagerFactory((KeyStore) null));
  }

  private static X509TrustManager trustManagerFor(List<X509Certificate> certs) throws Exception {
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    ks.load(null, null);
    int i = 0;
    for (X509Certificate cert : certs) {
      ks.setCertificateEntry("otlp-ca-" + (i++), cert);
    }
    return firstX509(loadTrustManagerFactory(ks));
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
   * Trust manager that accepts a certificate if <em>any</em> delegate accepts it. Delegates
   * are consulted in order (JDK default first, extra CAs second), so public endpoints keep
   * validating normally and internally-signed ones validate against the extra CAs.
   */
  private static final class CompositeX509TrustManager implements X509TrustManager {
    private final List<X509TrustManager> delegates;

    CompositeX509TrustManager(List<X509TrustManager> delegates) {
      this.delegates = delegates;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      java.security.cert.CertificateException last = null;
      for (X509TrustManager tm : delegates) {
        try {
          tm.checkServerTrusted(chain, authType);
          return;
        } catch (java.security.cert.CertificateException e) {
          last = e;
        }
      }
      throw (last != null) ? last : new java.security.cert.CertificateException("No trust managers");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      java.security.cert.CertificateException last = null;
      for (X509TrustManager tm : delegates) {
        try {
          tm.checkClientTrusted(chain, authType);
          return;
        } catch (java.security.cert.CertificateException e) {
          last = e;
        }
      }
      throw (last != null) ? last : new java.security.cert.CertificateException("No trust managers");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      List<X509Certificate> issuers = new ArrayList<>();
      for (X509TrustManager tm : delegates) {
        for (X509Certificate issuer : tm.getAcceptedIssuers()) {
          issuers.add(issuer);
        }
      }
      return issuers.toArray(new X509Certificate[0]);
    }
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
