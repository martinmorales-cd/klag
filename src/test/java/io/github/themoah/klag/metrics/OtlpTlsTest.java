package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link OtlpTls}.
 *
 * <p>Env-var stubbing is intentionally avoided (no portable JVM API); the loader is exercised
 * directly via {@link OtlpTls#buildAdditiveContext} / {@link OtlpTls#buildAdditiveTrustManager}.
 * Certificate fixtures are real CAs exported from the running JDK's default trust store, so the
 * tests run anywhere a JDK is installed.
 */
class OtlpTlsTest {

  @TempDir
  Path tmp;

  private static X509TrustManager defaultTrustManager() throws Exception {
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init((KeyStore) null);
    for (var tm : tmf.getTrustManagers()) {
      if (tm instanceof X509TrustManager x509) {
        return x509;
      }
    }
    throw new IllegalStateException("no default X509TrustManager");
  }

  private static String toPem(X509Certificate cert) throws Exception {
    String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded());
    return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
  }

  @Test
  void additiveTrust_acceptedIssuersAreSupersetOfDefaults() throws Exception {
    X509Certificate[] defaults = defaultTrustManager().getAcceptedIssuers();
    assumeTrue(defaults.length >= 2, "JDK trust store has too few CAs for this test");

    // A single JDK CA used as the "extra" bundle: whatever the defaults are, the composite must
    // still accept every one of them AND carry at least one more accepted issuer than defaults —
    // so dropping the extras (a regression) would be detectable.
    Path pem = tmp.resolve("ca.pem");
    Files.writeString(pem, toPem(defaults[0]));

    X509Certificate[] combined = OtlpTls.buildAdditiveTrustManager(pem).getAcceptedIssuers();

    Set<X509Certificate> combinedSet = new HashSet<>(Arrays.asList(combined));
    for (X509Certificate def : defaults) {
      assertTrue(combinedSet.contains(def), "composite dropped a JDK default issuer");
    }
    assertTrue(combined.length > defaults.length,
        "composite should add the extra CA on top of the defaults");
  }

  @Test
  void additiveContext_emptyFile_yieldsDefaultsOnlyContext() throws Exception {
    Path pem = tmp.resolve("empty.pem");
    Files.writeString(pem, "");

    SSLContext ctx = OtlpTls.buildAdditiveContext(pem);

    // Trust is never reduced: an empty bundle still yields a usable defaults-only context whose
    // accepted issuers match the JDK defaults exactly.
    assertNotNull(ctx);
    assertNotNull(ctx.getSocketFactory());
    assertTrue(OtlpTls.buildAdditiveTrustManager(pem).getAcceptedIssuers().length
        == defaultTrustManager().getAcceptedIssuers().length);
  }

  @Test
  void additiveContext_invalidContent_throws() throws Exception {
    Path pem = tmp.resolve("garbage.pem");
    Files.writeString(pem, "this is not a certificate");
    assertThrows(CertificateException.class, () -> OtlpTls.buildAdditiveContext(pem));
  }

  @Test
  void configuredCertPath_unset_isNullOrBlank() {
    // Guard: only meaningful when the test JVM has neither cert env var set.
    assumeTrue(System.getenv("OTLP_CA_CERT_PATH") == null
        && System.getenv("OTEL_EXPORTER_OTLP_CERTIFICATE") == null,
        "cert-path env var set in this environment");
    String path = OtlpTls.configuredCertPath();
    assertTrue(path == null || path.isBlank(), "expected no configured cert path in test env");
  }

  @Test
  void sslContextFromEnvironment_unset_isEmpty() {
    assumeTrue(System.getenv("OTLP_CA_CERT_PATH") == null
        && System.getenv("OTEL_EXPORTER_OTLP_CERTIFICATE") == null,
        "cert-path env var set in this environment");
    assertTrue(OtlpTls.sslContextFromEnvironment().isEmpty());
  }
}
