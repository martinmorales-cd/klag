package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link OtlpTls}.
 *
 * <p>Env-var stubbing is intentionally avoided (no portable JVM API); the loader is exercised
 * directly via {@link OtlpTls#buildAdditiveContext}. Certificate fixtures are real CAs exported
 * from the running JDK's default trust store, so the tests run anywhere a JDK is installed.
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

  private static String toPem(java.security.cert.X509Certificate cert) throws Exception {
    String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded());
    return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
  }

  @Test
  void additiveContext_trustsDefaultsPlusExtraCerts() throws Exception {
    var defaultIssuers = defaultTrustManager().getAcceptedIssuers();
    assumeTrue(defaultIssuers.length >= 2, "JDK trust store has too few CAs for this test");

    // Use two real CAs as the "extra" bundle so we exercise the multi-cert path.
    Path pem = tmp.resolve("ca-bundle.pem");
    Files.writeString(pem, toPem(defaultIssuers[0]) + toPem(defaultIssuers[1]));

    SSLContext ctx = OtlpTls.buildAdditiveContext(pem);
    assertNotNull(ctx);
    assertNotNull(ctx.getSocketFactory());
  }

  @Test
  void additiveContext_emptyFile_yieldsDefaultsOnlyContext() throws Exception {
    Path pem = tmp.resolve("empty.pem");
    Files.writeString(pem, "");

    SSLContext ctx = OtlpTls.buildAdditiveContext(pem);

    // Trust is never reduced: an empty bundle still yields a usable defaults-only context.
    assertNotNull(ctx);
    assertNotNull(ctx.getSocketFactory());
  }

  @Test
  void additiveContext_invalidContent_throws() {
    Path pem = tmp.resolve("garbage.pem");
    assertThrows(Exception.class, () -> {
      Files.writeString(pem, "this is not a certificate");
      OtlpTls.buildAdditiveContext(pem);
    });
  }

  @Test
  void configuredCertPath_unset_isNullOrBlank() {
    // Neither env var is expected in the test JVM; loader should treat that as "no custom trust".
    String path = OtlpTls.configuredCertPath();
    assertTrue(path == null || path.isBlank(), "expected no configured cert path in test env");
  }

  @Test
  void sslContextFromEnvironment_unset_isEmpty() {
    assertTrue(OtlpTls.sslContextFromEnvironment().isEmpty());
  }
}
