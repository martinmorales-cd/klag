package io.github.themoah.klag.metrics;

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
  void additiveTrust_neverReducesDefaultIssuers() throws Exception {
    X509Certificate[] defaults = defaultTrustManager().getAcceptedIssuers();
    assumeTrue(defaults.length >= 1, "JDK trust store has no CAs for this test");

    // Feed one real JDK CA back in as the "extra" bundle (guaranteed parseable, runs anywhere).
    // The single merged PKIX KeyStore de-dupes trust anchors, so an extra that duplicates a
    // default does not grow the count — the invariant that matters is that trust is never
    // narrowed: every JDK default issuer must still be accepted after the merge.
    Path pem = tmp.resolve("ca.pem");
    Files.writeString(pem, toPem(defaults[0]));

    X509Certificate[] combined = OtlpTls.buildAdditiveTrustManager(pem).getAcceptedIssuers();

    Set<X509Certificate> combinedSet = new HashSet<>(Arrays.asList(combined));
    for (X509Certificate def : defaults) {
      assertTrue(combinedSet.contains(def), "additive trust dropped a JDK default issuer");
    }
  }

  @Test
  void additiveContext_emptyFile_throws() throws Exception {
    Path pem = tmp.resolve("empty.pem");
    Files.writeString(pem, "");

    // A configured-but-cert-less bundle is a misconfiguration: rather than silently yield a
    // defaults-only context (which would log customCaTrust: true while trusting nothing extra),
    // it throws so the caller falls back to the stock JVM-default exporter path.
    assertThrows(CertificateException.class, () -> OtlpTls.buildAdditiveContext(pem));
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
