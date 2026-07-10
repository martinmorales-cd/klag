package io.github.themoah.klag;

import io.github.themoah.klag.config.VertxConfig;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom launcher for Klag with virtual threads support.
 */
public class KlagLauncher {

  private static final Logger log = LoggerFactory.getLogger(KlagLauncher.class);

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

  public static void main(String[] args) {
    VertxOptions vertxOptions = VertxConfig.createVertxOptions();
    Vertx vertx = Vertx.vertx(vertxOptions);

    // Unlike io.vertx.core.Launcher, a plain main() registers no shutdown hook. Without
    // one, SIGTERM (each Kubernetes pod stop / docker stop) exits the JVM without running
    // MainVerticle.stop(), dropping in-flight metric publishes and skipping the Kafka
    // admin client / HTTP server teardown.
    CountDownLatch running = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("Shutdown signal received, closing Vert.x");
      try {
        vertx.close().toCompletionStage().toCompletableFuture()
          .get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        log.info("Shutdown complete");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        log.warn("Graceful shutdown did not complete cleanly: {}", e.toString());
      } finally {
        running.countDown();
      }
    }, "klag-shutdown"));

    DeploymentOptions deploymentOptions = VertxConfig.createDeploymentOptions();

    try {
      String deploymentId = vertx.deployVerticle(new MainVerticle(), deploymentOptions)
        .toCompletionStage().toCompletableFuture()
        .get();
      log.info("MainVerticle deployed with ID: {}", deploymentId);
      // Block until SIGTERM/SIGINT. With VIRTUAL_THREAD deployment the main thread would
      // otherwise return immediately and the JVM would exit while the verticle is still
      // starting (event-loop threads keep the process alive; virtual threads do not).
      running.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for shutdown");
    } catch (Exception err) {
      log.error("Failed to deploy MainVerticle", err);
      try {
        vertx.close().toCompletionStage().toCompletableFuture()
          .get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (Exception closeErr) {
        log.warn("Error closing Vert.x after startup failure: {}", closeErr.toString());
      }
      System.exit(1);
    }
  }
}
