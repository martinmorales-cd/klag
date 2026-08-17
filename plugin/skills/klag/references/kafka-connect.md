# Connecting Klag to Kafka

## Finding the bootstrap servers

Ask only if you cannot discover it.

- **Strimzi**: `kubectl get kafka -A` then
  `kubectl get svc -n <ns> | grep kafka-bootstrap` → `<cluster>-kafka-bootstrap.<ns>:9092`
  (plain) or `:9093` (TLS). Strimzi's `Kafka` CR also lists listeners under
  `.status.listeners[].bootstrapServers`.
- **Any operator/manual deploy**: `kubectl get svc -A | grep -i kafka`.
- **Confluent Cloud**: `<id>.<region>.<cloud>.confluent.cloud:9092`, always `SASL_SSL`/`PLAIN`.
- **MSK**: `aws kafka get-bootstrap-brokers --cluster-arn <arn>`.
- **Local compose**: internal listener (`kafka:29092`), not the host-mapped `localhost:9092`,
  when Klag runs in the same compose network.

Klag must reach the *advertised* listener, not just the bootstrap host — a bootstrap that
resolves but advertises unreachable broker hostnames shows up as `/readyz` 503 with timeouts.

## Auth matrix

| Kafka | `KAFKA_SECURITY_PROTOCOL` | `KAFKA_SASL_MECHANISM` | extra |
|---|---|---|---|
| local / dev | *(unset)* | — | — |
| TLS only | `SSL` | — | truststore if the CA is private |
| SASL over plaintext | `SASL_PLAINTEXT` | `PLAIN` or `SCRAM-SHA-512` | JAAS |
| SASL over TLS (Confluent Cloud, most prod) | `SASL_SSL` | `PLAIN` or `SCRAM-SHA-512` | JAAS |
| MSK IAM | **unsupported** | — | see below |

JAAS strings:

```
# PLAIN (Confluent Cloud: username = API key, password = API secret)
org.apache.kafka.common.security.plain.PlainLoginModule required username="KEY" password="SECRET";

# SCRAM
org.apache.kafka.common.security.scram.ScramLoginModule required username="USER" password="PASS";
```

Env form on docker/jar:

```bash
-e KAFKA_SECURITY_PROTOCOL=SASL_SSL \
-e KAFKA_SASL_MECHANISM=PLAIN \
-e KAFKA_SASL_JAAS_CONFIG='org.apache.kafka.common.security.plain.PlainLoginModule required username="KEY" password="SECRET";'
```

Helm form — credentials go through a Secret, never inline in a committed `values.yaml`:

```yaml
kafka:
  bootstrapServers: "pkc-xxxxx.eu-central-1.aws.confluent.cloud:9092"
  securityProtocol: SASL_SSL
  saslMechanism: PLAIN
  existingSecret: klag-kafka          # keys: jaas-config, truststore-password
```

```bash
kubectl -n "$NS" create secret generic klag-kafka \
  --from-literal=jaas-config='org.apache.kafka.common.security.plain.PlainLoginModule required username="KEY" password="SECRET";'
```

The chart also accepts `kafka.saslJaasConfig` (it creates the Secret for you) — acceptable
for a throwaway POC via `--set`, not for a file under version control. Key names are
overridable with `kafka.secretKeys.jaasConfig` / `kafka.secretKeys.truststorePassword`.

Private CA truststore: set `kafka.sslTruststoreLocation` and mount the JKS with
`extraVolumes` / `extraVolumeMounts`. Note `readOnlyRootFilesystem: true` is the default —
mount read-only, don't write into the image.

Anything else the Kafka client supports works through the generic mapping: `KAFKA_X_Y_Z` →
`kafka.x.y.z` (chart: `extraEnv`).

### MSK

SASL/SCRAM and TLS MSK clusters work like any other. **IAM auth does not** — the
`aws-msk-iam-auth` login module is not on Klag's classpath, so
`AWS_MSK_IAM`/`sasl.client.callback.handler.class` cannot resolve. Use SCRAM credentials
from Secrets Manager, or unauthenticated TLS inside the VPC.

## ACLs

Read-only, `DESCRIBE` on three resources. Cluster `DESCRIBE` is **always** required, even
with group filtering, because `listConsumerGroups()` runs before app-level filtering.

| Resource | Operation |
|---|---|
| CLUSTER | DESCRIBE |
| TOPIC (`*` or prefixed) | DESCRIBE |
| GROUP (`*` or prefixed) | DESCRIBE |

Commands for self-managed (`kafka-acls`) and Confluent Cloud (`confluent kafka acl create`)
are at `https://klag.dev/kafka/acl-permissions/`.

Asymmetric ACLs are a real failure mode: if offsets are readable but the topic is not, Klag
treats the topic as deleted and retires its series. Grant topic DESCRIBE for every topic the
monitored groups consume.

## Verifying the connection

```bash
curl -s "$KLAG_URL/readyz"            # 200 = Kafka reachable, 503 = not
curl -s "$KLAG_URL/metrics" | grep -c '^klag_consumer_lag{'
```

`/readyz` 503 → bad bootstrap, unreachable advertised listeners, wrong protocol, or bad
credentials; check Klag's logs for `TimeoutException` (network) vs
`SaslAuthenticationException` (credentials) vs `TopicAuthorizationException` (ACLs).

200 but zero series → `METRICS_REPORTER` is unset (default `none`), no group has committed
offsets yet, or `METRICS_GROUP_FILTER` excludes everything.
