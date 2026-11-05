# charge-fee-service

> A stateless calculator that owns the rules deciding what a bank transfer costs: it applies the first matching fee policy and returns the fee, a machine-readable `feeType` and a human-readable `description`. Application port **8085**, management/metrics port **18085**.

- [What It Does](#what-it-does)
- [Architecture At A Glance](#architecture-at-a-glance)
- [HTTP API](#http-api)
- [Error Response Shape](#error-response-shape)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Configuration](#configuration)
- [Behaviour Rules](#behaviour-rules)
- [The Fee Rules](#the-fee-rules)
- [Running It](#running-it)
- [API Quick Reference (curl examples)](#api-quick-reference-curl-examples)
- [Configuration Reference (quick table)](#configuration-reference-quick-table)
- [Troubleshooting](#troubleshooting)
- [Notes and Known Limitations](#notes-and-known-limitations)

## What It Does

The charge-fee service is a **pure calculator**. It owns the rules that decide what a bank transfer costs, and nothing else: no accounts, no ledger, no transfer state. It receives a transfer description — from account, to account, amount, and whether both accounts belong to the same owner — applies the first matching fee policy, and returns the fee together with a machine-readable `feeType` and a human-readable `description`.

Because it is a pure function of its input, it keeps **no state at all** and therefore **persists nothing**. There is no database, no Redis, no repository and no SQL file in the service. The only thing it has is configuration.

It is invoked synchronously over `POST /internal/fees/calculate`, which is what the fund-transfer service (`fund-transfer-service`, port 8082) uses through `ChargeFeeClientImpl`, and asynchronously over Kafka: it consumes `fee.commands` and publishes `fee.events`. The two paths share the same `FeeCalculationServiceImpl` and therefore always produce the same answer for the same input.

It does **not** own the money. It does not debit an account, it does not check a balance, and it does not approve a transfer. It answers one question — "what does this transfer cost?" — and answers it identically every time.

## Architecture At A Glance

```
REST path
  fund-transfer-service --POST /internal/fees/calculate--> charge-fee-service
                                                       |
                                        FeeCalculationServiceImpl
                                          |- SelfTransferFeePolicy   (sameOwner == true)
                                          |- OtherAccountFeePolicy   (sameOwner == false)
                                                       |
                                              {"fee", "feeType", "description"}
                                              (nothing is written anywhere)

Kafka path
  fund-transfer-service --CalculateFee--> fee.commands --> charge-fee-service
  fund-transfer-service <--FeeCalculated- fee.events  <-- charge-fee-service
```

| Depends on | Depended on by |
|---|---|
| Kafka (`localhost:9092`) — consumes `fee.commands`, publishes `fee.events` | `fund-transfer-service` (port 8082) — `ChargeFeeClientImpl` calls `POST /internal/fees/calculate` |
| Eureka (`localhost:8761`) — service registration | The api-gateway (port 8080) blocks `/internal/**`, so the endpoint is not reachable from a browser. |

**No database. No Redis.** The service has no outbound HTTP client at all — no `RestClient`, no `WebClient`.

## HTTP API

Base path: `/internal/fees`. One endpoint, `POST`, accepting `application/json`. It does not require authentication — the api-gateway blocks `/internal/**` instead.

### `POST /internal/fees/calculate`

Calculates the fee for one transfer. Returns **200** on success.

Request body — `FeeRequest`:

| Field | Type | Required | Validation message |
|---|---|---|---|
| `fromAccountNumber` | `String` | yes (`@NotBlank`) | `"fromAccountNumber is required"` |
| `fromAccountNumber` | `String` | (`@Size(max = 50)`) | `"fromAccountNumber must be at most 50 characters"` |
| `toAccountNumber` | `String` | yes (`@NotBlank`) | `"toAccountNumber is required"` |
| `toAccountNumber` | `String` | (`@Size(max = 50)`) | `"toAccountNumber must be at most 50 characters"` |
| `amount` | `BigDecimal` | yes (`@NotNull`) | `"amount is required"` |
| `amount` | `BigDecimal` | (`@DecimalMin("0.01")`) | `"amount must be at least 0.01"` |
| `amount` | `BigDecimal` | (`@Digits(integer = 15, fraction = 2)`) | `"amount allows at most 2 decimal places"` |
| `sameOwner` | `Boolean` | yes (`@NotNull`) | `"sameOwner is required"` |

Response body — `FeeResponse`:

| Field | Type | Meaning |
|---|---|---|
| `fee` | `BigDecimal` | The fee, always with 2 decimal places. |
| `feeType` | `String` | `SELF_TRANSFER` or `OTHER_ACCOUNT`. |
| `description` | `String` | A human-readable explanation of the fee. |

Example response:

```json
{"fee":10.00,"feeType":"OTHER_ACCOUNT","description":"Fee for transfers to another customer's account"}
```

A transfer between your own accounts:

```json
{"fee":0.00,"feeType":"SELF_TRANSFER","description":"No fee for transfers between your own accounts"}
```

Status codes:

| Status | When | `message` |
|---|---|---|
| **200** | Success | — |
| **400** | Bean validation failure | `"<field>: <message>; ..."` — the individual messages joined with `"; "` |
| **400** | Malformed JSON body | `"Malformed request body"` |
| **404** | Unknown path | `"Not found"` |
| **500** | Anything else | `"Something went wrong"` (stack trace logged as `"Unhandled error in the charge fee service"`) |

The `500` case includes `IllegalStateException("No fee policy matches this request")`, which happens when no policy matches — in practice only when `sameOwner` is null, since `sameOwner` is `@NotNull` at the HTTP layer.

### Error Response Shape

Every non-2xx response uses the same body — `ErrorResponse`:

| Field | Type | Notes |
|---|---|---|
| `timestamp` | `LocalDateTime` | Server-local time of the failure. Serialised as `2026-11-02T10:15:30.123456`. |
| `status` | `int` | The HTTP status code, repeated in the body. |
| `error` | `String` | The HTTP reason phrase, e.g. `"Bad Request"`. |
| `message` | `String` | The exact human-readable explanation (see the table above). |

Example:

```json
{
  "timestamp": "2026-11-02T10:15:30.123456",
  "status": 400,
  "error": "Bad Request",
  "message": "amount must be at least 0.01"
}
```

## Database

**charge-fee-service persists NOTHING.**

* `pom.xml` has no `spring-boot-starter-data-jpa`, no `spring-boot-starter-jdbc` and no `postgresql` driver. Its dependencies are `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `spring-cloud-starter-netflix-eureka-client`, `spring-boot-starter-test` and `spring-kafka`.
* `application.yaml` has no `spring.datasource` block.
* There is no `@Entity`, no `JpaRepository`, no repository of any kind and no SQL or migration file anywhere in the service.
* `run-all.sh` lists the service as `"charge-fee-service|8085|"` — the third field (the database) is empty, so it is started without any `SPRING_DATASOURCE_*` variables.

The service is completely stateless. `FeeCalculationServiceImpl` holds only the injected `List<FeePolicy>`; `CalculateFeeHandler` holds the service, the publisher, the validator and the meter registry. The Kafka path is explicitly stateless too — the source comment reads: *"A pure calculation, so a repeated command gives an identical answer. Nothing to remember."* There is no idempotency table and no processed-events table.

The only persistence in the whole service is the **configuration** of the other-account fee rule.

## Kafka Topics

Topics are declared in `TopicNames`: `FEE_COMMANDS = "fee.commands"` (consumed), `FEE_EVENTS = "fee.events"` (published), `DLT_SUFFIX = ".DLT"`.

Every message on the bus uses the same envelope — `EventEnvelope`:

| Field | Type | Meaning |
|---|---|---|
| `eventId` | `String` | Fresh UUID for the message. |
| `eventType` | `String` | `"CalculateFee"` or `"FeeCalculated"`. |
| `version` | `int` | Always `1`. |
| `correlationId` | `String` | The `transferId`. |
| `causationId` | `String` | The `eventId` of the message that caused this one. |
| `occurredAt` | `Instant` | When the message was built. |
| `producer` | `String` | `app.messaging.producer-name`, i.e. `"charge-fee-service"`. |
| `payload` | `JsonNode` | The type-specific payload. |

### Consumed: `fee.commands`

Listener: `FeeCommandsConsumer`, `@KafkaListener(topics = TopicNames.FEE_COMMANDS)`. Only `eventType == "CalculateFee"` is handled; any other type is ignored with the debug line `"Ignoring unknown command type {}"`.

Command type **`CalculateFee`** — payload `CalculateFeeCommand`:

| Field | Type | Meaning |
|---|---|---|
| `transferId` | `String` | The transfer the fee is for. Also the Kafka key. |
| `fromAccountNumber` | `String` | Source account. |
| `toAccountNumber` | `String` | Target account. |
| `amount` | `BigDecimal` | The amount. |
| `sameOwner` | `Boolean` | `true` when both accounts belong to the same customer. |

Validation in `CalculateFeeHandler.handle(...)`:

| Condition | Message |
|---|---|
| `transferId` null, blank or `trim().length() > 80` | `"CalculateFee: transferId is missing or too long"` |
| The `FeeRequest` built from the command violates the Jakarta constraints | `"CalculateFee: " + problems`, where `problems` is the sorted list of `"<propertyPath>: <message>"` joined with `"; "` |

`InvalidEventException` is registered as **not retryable** in `KafkaConsumerConfig`, so such a message goes straight to `fee.commands.DLT`.

The envelope-level errors are `"Empty message"`, `"Message is not valid JSON"`, `"Message has no eventId or eventType"`, `"Message has no payload"` and `"Payload does not match CalculateFee"`.

### Published: `fee.events`

Event type **`FeeCalculated`** — payload `FeeCalculatedPayload`:

| Field | Type | Meaning |
|---|---|---|
| `transferId` | `String` | The transfer the fee was calculated for. Also the Kafka key. |
| `fee` | `BigDecimal` | The fee, serialised as a **string** in JSON (`@JsonFormat(shape = JsonFormat.Shape.STRING)`) to avoid float precision loss. |
| `feeType` | `String` | `OTHER_ACCOUNT` or `SELF_TRANSFER`. |

`correlationId` is the `transferId` and `causationId` is the command's `eventId`.

Metric: `fee.calculated` counter with the tag `type` = the `feeType` (`OTHER_ACCOUNT` / `SELF_TRANSFER`).

### Retries, dead letters and metrics

`KafkaConsumerConfig` uses `ExponentialBackOffWithMaxRetries(4)` (initial 1 s, multiplier 2.0, max 10 s) and parks a failed record on `fee.commands.DLT` on the same partition. `InvalidEventException` is not retryable.

Kafka client tuning: consumer `group-id: charge-fee-service`, `auto-offset-reset: earliest`, `enable-auto-commit: false`, `ack-mode: record`, `concurrency: 3`; producer `acks: all`, `enable.idempotence: true`, `linger.ms: 0`, `max.block.ms: 15000`, `request.timeout.ms: 5000`, `delivery.timeout.ms: 10000`.

Every topic has **3 partitions**. `fee.commands` and `fee.events` are kept for 7 days with a 14-day DLT retention.

## Configuration

### Server and Spring properties

| Name | Default | Meaning |
|---|---|---|
| `server.port` | `8085` | Application port. Overridden by `SERVER_PORT`. |
| `management.server.port` | `18085` | Actuator / Prometheus port. |
| `spring.application.name` | `charge-fee-service` | Name registered with Eureka. |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` | Broker list. |
| `spring.kafka.consumer.group-id` | `charge-fee-service` | Consumer group. |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | Where a new group starts. |
| `spring.kafka.consumer.enable-auto-commit` | `false` | Offsets are committed by the listener. |
| `spring.kafka.listener.ack-mode` | `record` | Ack per record. |
| `spring.kafka.listener.concurrency` | `3` | Listener threads. |
| `spring.kafka.producer.acks` | `all` | Full durability. |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | Idempotent producer. |
| `spring.kafka.producer.properties.linger.ms` | `0` | Send immediately. |
| `spring.kafka.producer.properties.max.block.ms` | `15000` | Max block on send. |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` | Request timeout. |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` | Delivery timeout. |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL:http://localhost:8761/eureka}` | Discovery server. |
| `eureka.instance.prefer-ip-address` | `true` | Register by IP. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Exposed actuator endpoints. |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | Latency histograms. |

Note: this service does **not** import a `.env` file — `spring.config.import` is absent, so it only reads environment variables.

### Custom `app.*` properties

| Name | Default | Meaning |
|---|---|---|
| `app.messaging.producer-name` | `charge-fee-service` | The `producer` field of the published envelope. |
| `app.fee.other-account.flat-fee` | `${FEE_OTHER_FLAT:10.00}` | Flat part of the fee for a transfer to another customer's account. |
| `app.fee.other-account.percentage` | `${FEE_OTHER_PERCENTAGE:0.00}` | Percentage part of the fee (0–100; `1.5` means 1.5 %). |
| `app.fee.other-account.max-fee` | `${FEE_OTHER_MAX:0.00}` | Upper limit for the fee; `0` means **no limit**. |

Binding class: `OtherAccountFeeProperties` — `@Validated @ConfigurationProperties(prefix = "app.fee.other-account")`, record `OtherAccountFeeProperties(BigDecimal flatFee, BigDecimal percentage, BigDecimal maxFee)` with `@NotNull @DecimalMin("0.00")` on `flatFee`, `@NotNull @DecimalMin("0.00") @DecimalMax("100.00")` on `percentage`, and `@NotNull @DecimalMin("0.00")` on `maxFee`. Invalid values fail the startup.

### Environment variables

| Name | Default | Used for |
|---|---|---|
| `FEE_OTHER_FLAT` | `10.00` | `app.fee.other-account.flat-fee` |
| `FEE_OTHER_PERCENTAGE` | `0.00` | `app.fee.other-account.percentage` |
| `FEE_OTHER_MAX` | `0.00` | `app.fee.other-account.max-fee` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `spring.kafka.bootstrap-servers` |
| `EUREKA_URL` | `http://localhost:8761/eureka` | `eureka.client.service-url.defaultZone` |
| `SERVER_PORT` | `8085` (set by `run-all.sh`) | Overrides `server.port`. |

#### Environment variables you must set

Every variable above has a working default, so the service starts with none of them set. The three fee variables are the ones you will want to change in a real deployment — the built-in defaults produce a flat `10.00` fee for every transfer to another customer's account.

`FEE_OTHER_MAX` is the easy one to get wrong: **`0.00` means no cap**, not "a cap of zero". Set it to a positive value to cap the fee.

## Behaviour Rules

| Rule | Value | Notes |
|---|---|---|
| The service is stateless and side-effect free | — | Apart from publishing the result event, a calculation has no effect. |
| Idempotency | not needed, not implemented | Repeating the same `CalculateFee` command yields the same `FeeCalculated` event again. The saga may see the fact twice; it is a pure calculation. There is no idempotency table. |
| Policy selection | first match wins | `FeeCalculationServiceImpl` takes the **first** `FeePolicy` from the injected list whose `supports(request)` returns `true`. |
| No matching policy | HTTP 500 | `IllegalStateException("No fee policy matches this request")`. |
| Rounding | `RoundingMode.HALF_UP` everywhere | The percentage part is rounded to 2 decimals **before** the flat fee is added, and the total is rounded to 2 decimals again. |
| `sameOwner` must not be null | enforced | `supports()` uses `Boolean.FALSE.equals(...)` / `Boolean.TRUE.equals(...)`, so a null `sameOwner` matches no policy. `sameOwner` is `@NotNull` at the HTTP layer, and the Kafka layer validates the built `FeeRequest` with the same constraints. |
| The amount is not touched by the service | — | The service never debits or checks the amount; it only uses it as the base of the percentage. |
| The account numbers are not validated for existence | — | They are only checked for presence and length. |

### Error codes

This service produces **no** string error codes. Everything is an HTTP status plus the `ErrorResponse` body:

| Status | Meaning |
|---|---|
| `400` | Invalid `FeeRequest` (missing/blank account numbers, `amount` < 0.01, more than 2 decimal places, missing `sameOwner`), or malformed JSON |
| `404` | Unknown path |
| `500` | Unexpected error, including "no policy matched" |

The `feeType` values are the only machine-readable output: `OTHER_ACCOUNT` and `SELF_TRANSFER`. There is no string error code such as `OTP_DELIVERY_FAILED` in this service — that literal lives in `fund-transfer-service`.

The Kafka validation messages are:

| Condition | Message |
|---|---|
| `transferId` missing or longer than 80 characters | `"CalculateFee: transferId is missing or too long"` |
| The built `FeeRequest` violates a constraint | `"CalculateFee: <property>: <message>; ..."` |
| Envelope problems | `"Empty message"`, `"Message is not valid JSON"`, `"Message has no eventId or eventType"`, `"Message has no payload"`, `"Payload does not match CalculateFee"` |

## The Fee Rules

### The exact formula

`OtherAccountFeePolicy` computes:

```java
BigDecimal percentPart = request.amount()
        .multiply(properties.percentage())
        .divide(HUNDRED, 2, RoundingMode.HALF_UP);      // HUNDRED = 100

BigDecimal fee = properties.flatFee()
        .add(percentPart)
        .setScale(2, RoundingMode.HALF_UP);

BigDecimal cap = properties.maxFee();
if (cap.signum() > 0 && fee.compareTo(cap) > 0) {
    fee = cap.setScale(2, RoundingMode.HALF_UP);
}
```

Written out:

```
percentPart = round_half_up(amount × percentage / 100, 2)
fee        = round_half_up(flatFee + percentPart, 2)
if maxFee > 0 and fee > maxFee:  fee = maxFee (scale 2)
```

Rounding is `RoundingMode.HALF_UP` everywhere. The percentage part is rounded to 2 decimals **before** the flat fee is added, and the total is rounded to 2 decimals again.

### The complete policy table

There are exactly **two** policies and **one** configurable rule. There are no other bands, tiers or volume discounts.

| # | Rule / band | Applies when | Flat fee | Percentage | Cap | `feeType` | `description` |
|---|---|---|---|---|---|---|---|
| 1 | **Self transfer** (`SelfTransferFeePolicy`) | `sameOwner == TRUE` | `0` (fixed, `BigDecimal.ZERO.setScale(2)` → `0.00`) | 0 | none | `SELF_TRANSFER` | `"No fee for transfers between your own accounts"` |
| 2 | **Transfer to another customer's account** (`OtherAccountFeePolicy`) | `sameOwner == FALSE` | `app.fee.other-account.flat-fee` = `FEE_OTHER_FLAT` = **10.00** | `app.fee.other-account.percentage` = `FEE_OTHER_PERCENTAGE` = **0.00** (i.e. 0 % by default) | `app.fee.other-account.max-fee` = `FEE_OTHER_MAX` = **0.00** → **0 means no cap** | `OTHER_ACCOUNT` | `"Fee for transfers to another customer's account"` |

### Worked examples

All of these come from `OtherAccountFeePolicyTest`:

| flat | percentage | max | amount | sameOwner | result |
|---|---|---|---|---|---|
| `10.00` | `0.00` | `0.00` | `100.00` | false | fee `10.00`, `feeType` `OTHER_ACCOUNT` |
| `10.00` | `1.5` | `0.00` | `1000.00` | false | fee `25.00` |
| `10.00` | `1.5` | `20.00` | `1000.00` | false | fee `20.00` (capped) |
| `0.00` | `1.5` | `0.00` | `33.33` | false | fee `0.50` (half-up rounding) |
| — | — | — | `999999.99` | true | fee `0.00`, `feeType` `SELF_TRANSFER` |

The third row shows the cap: `10.00 + 15.00 = 25.00`, which is above `20.00`, so the fee becomes `20.00`.
The fourth row shows the rounding: `33.33 × 1.5 / 100 = 0.49995`, which rounds half-up to `0.50`.

## Running It

### Prerequisites

* Java 17
* Maven 3.8+ (or use the bundled `./mvnw`)
* Kafka on `localhost:9092`
* Eureka on `localhost:8761`
* **No database. No Redis.**

### Build

```bash
cd charge-fee-service
mvn -q clean package -DskipTests
# or
./mvnw -q clean package -DskipTests
```

Test: `mvn test` — `OtherAccountFeePolicyTest` (6 unit tests) plus `ChargeFeeServiceApplicationTests#contextLoads`.
Jar: `charge-fee-service/target/charge-fee-service-0.0.1-SNAPSHOT.jar`.

### Run

```bash
cd charge-fee-service
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export EUREKA_URL=http://localhost:8761/eureka
export FEE_OTHER_FLAT=10.00
export FEE_OTHER_PERCENTAGE=0.00
export FEE_OTHER_MAX=0.00
export SERVER_PORT=8085

java -jar target/charge-fee-service-0.0.1-SNAPSHOT.jar
```

### Whole stack

From the repository root:

```bash
./run-all.sh          # start everything
./run-all.sh --build  # rebuild the jars first
./stop-all.sh         # stop everything
```

`run-all.sh` starts the service on **8085** with no datasource variables.

### Check that it is healthy

```bash
curl -s http://localhost:18085/actuator/health
```

```json
{"status":"UP"}
```

Prometheus metrics are on the same port at `/actuator/prometheus`. The `fee.calculated{type="OTHER_ACCOUNT"}` counter shows how many fees were calculated.

### Stop it

```bash
# foreground
Ctrl+C

# as a background process
kill <pid>
# or, for the whole stack
./stop-all.sh
```

## API Quick Reference (curl examples)

The `/internal/**` endpoints are blocked by the api-gateway, so call them directly on port 8085. No `Authorization` header is required — the endpoint does not authenticate.

A transfer to another customer's account (the default rule: flat 10.00, 0 %, no cap):

```bash
curl -i -X POST http://localhost:8085/internal/fees/calculate \
  -H 'Content-Type: application/json' \
  -d '{
        "fromAccountNumber": "ACC-1001",
        "toAccountNumber": "ACC-2002",
        "amount": 100.00,
        "sameOwner": false
      }'
```

```http
HTTP/1.1 200
{"fee":10.00,"feeType":"OTHER_ACCOUNT","description":"Fee for transfers to another customer's account"}
```

A transfer between your own accounts:

```bash
curl -i -X POST http://localhost:8085/internal/fees/calculate \
  -H 'Content-Type: application/json' \
  -d '{
        "fromAccountNumber": "ACC-1001",
        "toAccountNumber": "ACC-1002",
        "amount": 999999.99,
        "sameOwner": true
      }'
```

```http
HTTP/1.1 200
{"fee":0.00,"feeType":"SELF_TRANSFER","description":"No fee for transfers between your own accounts"}
```

An invalid amount returns HTTP 400:

```bash
curl -i -X POST http://localhost:8085/internal/fees/calculate \
  -H 'Content-Type: application/json' \
  -d '{
        "fromAccountNumber": "ACC-1001",
        "toAccountNumber": "ACC-2002",
        "amount": 0.001,
        "sameOwner": false
      }'
```

```http
HTTP/1.1 400
{"timestamp":"2026-11-02T10:15:30.123456","status":400,"error":"Bad Request","message":"amount must be at least 0.01; amount allows at most 2 decimal places"}
```

Several violations on the same field are joined with `"; "`; the order in which the validator reports them is not guaranteed.

An unknown path returns HTTP 404:

```bash
curl -i http://localhost:8085/internal/fees/nope
```

## Configuration Reference (quick table)

| Knob | Default | Change it to |
|---|---|---|
| `FEE_OTHER_FLAT` / `app.fee.other-account.flat-fee` | `10.00` | The flat part of the fee for another customer's account. |
| `FEE_OTHER_PERCENTAGE` / `app.fee.other-account.percentage` | `0.00` | The percentage part; `1.5` means 1.5 %. Must be between 0 and 100. |
| `FEE_OTHER_MAX` / `app.fee.other-account.max-fee` | `0.00` | The cap. **`0.00` means no cap.** A positive value caps the fee. |
| `app.messaging.producer-name` | `charge-fee-service` | The `producer` field of published envelopes. |
| `server.port` | `8085` | The application port. |
| `management.server.port` | `18085` | The actuator/metrics port. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | The broker list. |
| `EUREKA_URL` | `http://localhost:8761/eureka` | The discovery server. |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| HTTP 400 with `"sameOwner is required"` | `sameOwner` was missing or null. | Send `sameOwner` as a JSON boolean, `true` or `false`. |
| HTTP 400 with `"amount must be at least 0.01"` | The amount is below the minimum. | Send an amount of at least `0.01`. |
| HTTP 400 with `"amount allows at most 2 decimal places"` | The amount has more than 2 decimals, or more than 15 integer digits. | Round the amount to 2 decimals before sending it. |
| HTTP 400 with `"fromAccountNumber is required"` | The account number was blank. | Send both account numbers. |
| HTTP 400 with `"Malformed request body"` | The JSON could not be parsed. | Check the JSON syntax and the `Content-Type: application/json` header. |
| HTTP 500 with `"Something went wrong"` and `"No fee policy matches this request"` in the log | No policy matched — in practice a null `sameOwner`. | Send `sameOwner` explicitly. |
| The fee is always `10.00` | `FEE_OTHER_PERCENTAGE` is still `0.00`, so only the flat fee applies. | Set `FEE_OTHER_PERCENTAGE`, for example to `1.5` for 1.5 %. |
| The fee is capped when it should not be | `FEE_OTHER_MAX` is a positive value. | Set `FEE_OTHER_MAX=0.00` for no cap. |
| The service does not start and complains about the fee properties | `FEE_OTHER_PERCENTAGE` is above 100, or one of the three values is negative or missing. | Keep `percentage` between `0.00` and `100.00` and all three values at or above `0.00`. |
| The service is not in Eureka | `EUREKA_URL` points somewhere else, or the discovery service is down. | Check `EUREKA_URL` and the discovery service on port 8761. |
| No `FeeCalculated` event on `fee.events` | The command never arrived, or it was invalid and went straight to `fee.commands.DLT`. | Check the consumer group `charge-fee-service` and the `fee.commands.DLT` topic. |
| The same fee event is published twice | Expected: the service is stateless and does not deduplicate. | The saga treats it as a fact; no action is needed. |
| `404` with `"Not found"` | The path does not exist. | The only endpoint is `/internal/fees/calculate`. |

## Notes and Known Limitations

* **The service persists nothing.** There is no datasource, no repository and no migration file. If you see a `charge_fee_db` in your infrastructure, nothing in this service uses it.
* **The service has no outbound HTTP client at all** — no `RestClient` and no `WebClient`. It never calls another service.
* **There are only two fee rules.** The "other customer's account" rule is the only configurable one; the self-transfer rule is hard-coded to zero. There are no tiers, no volume discounts and no time-based pricing.
* **`FEE_OTHER_MAX=0.00` means "no cap"**, not "a cap of zero". This is the most likely misconfiguration.
* **The percentage is rounded before the flat fee is added.** With a percentage that produces more than 2 decimals, the total can differ by a cent from a naive calculation.
* **The default configuration charges a flat 10.00 for every transfer to another customer's account** with no percentage and no cap. That is a development default, not a pricing decision.
* **Invalid configuration values fail the startup** rather than being ignored: `flatFee` and `maxFee` must be at least `0.00`, and `percentage` must be between `0.00` and `100.00`.
* **The service does not import a `.env` file** (`spring.config.import` is absent), unlike `otp-service` and `notification-service`. It reads environment variables only.
* **A repeated command produces a repeated event.** There is no idempotency table; the source comment says explicitly *"A pure calculation, so a repeated command gives an identical answer. Nothing to remember."* Downstream consumers have to tolerate the duplicate.
* **The service does not validate that the accounts exist** or that the amount is affordable. It only uses the inputs to pick a rule and compute a number.
* **`fee` is published as a JSON string** on `fee.events` (`@JsonFormat(shape = JsonFormat.Shape.STRING)`) to avoid float precision loss. A consumer must parse it as a decimal, not as a number.

# Charge Fee Service

**Part of the Banking Microservices Project**

Developed and maintained by **Jahid Hasan**

- **Author:** Jahid Hasan
- **GitHub:** [Md-Jahid-Hasan-10](https://github.com/Md-Jahid-Hasan-10)
- **Repository:** [charge-fee-service](https://github.com/Md-Jahid-Hasan-10/charge-fee-service)
- **Project:** Banking Microservices
- **Application Port:** `8085`
- **Management/Metrics Port:** `18085`

> A stateless fee calculation service that determines bank transfer charges based on configurable fee policies.
