# 🔍 Spring N+1 Query Detector

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3%2B-green.svg)](https://spring.io/projects/spring-boot)

**The first complete, modern, open-source N+1 query detector for Spring Boot applications.**

Automatically detects N+1 query problems at runtime, provides actionable fix suggestions, and integrates with Spring Boot Actuator and Micrometer for production monitoring.

---

## ✨ Features

| Feature | Description |
|---|---|
| 🔎 **Runtime Detection** | Automatically detects N+1 patterns in every HTTP request |
| 💡 **Smart Fix Suggestions** | Generates code examples: JOIN FETCH, @EntityGraph, @BatchSize, etc. |
| 📊 **Actuator Endpoint** | `GET /actuator/nplus1` — browse violations in your browser |
| 📈 **Micrometer Metrics** | Counters, gauges, and distribution summaries for Grafana/Datadog |
| 🧪 **Test Assertions** | `@AssertNoNPlusOne` and `@ExpectQueries` for CI/CD pipelines |
| 🎯 **`@SuppressNPlusOne`** | Intentionally suppress detection for known patterns |
| ⚙️ **Zero Config** | Works out of the box — just add the dependency |
| 🔒 **Production Safe** | LOG mode by default, configurable thresholds, path exclusions |

---

## 🚀 Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.nour</groupId>
    <artifactId>nplus1-detector-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure (optional)

```yaml
# application.yml
nplus1:
  detector:
    enabled: true
    threshold: 3
    mode: LOG          # LOG | THROW | SILENT
    application-packages:
      - com.mycompany.myapp
    report-format: CONSOLE  # CONSOLE | JSON | BOTH
```

### 3. Run your app

That's it! N+1 violations will appear in your logs:

```
╔══════════════════════════════════════════════════════════════════╗
║                 🔍 N+1 QUERY DETECTOR REPORT                   ║
╚══════════════════════════════════════════════════════════════════╝
  Context       : GET /api/users
  Total Queries : 11
  Redundant     : 9 (could be eliminated)
  Violations    : 1 detected
  Wasted Time   : 45ms
────────────────────────────────────────────────────────────────────

  🔴 Violation #1 [CRITICAL]
  ├─ Occurrences : 10 duplicate queries
  ├─ Time Wasted : 45ms
  ├─ Origin      : com.myapp.service.UserService.findAll(UserService.java:42)
  ├─ SQL Sample  : select * from orders where user_id = ?
  ├─ 💡 Suggested Fixes:
  │   1. [JOIN FETCH] Use JOIN FETCH to load this association eagerly...
  │   2. [@EntityGraph] Add @EntityGraph to your repository method...
```

---

## 📖 Configuration Reference

| Property | Default | Description |
|---|---|---|
| `nplus1.detector.enabled` | `true` | Master switch |
| `nplus1.detector.threshold` | `3` | Min duplicate queries to flag |
| `nplus1.detector.mode` | `LOG` | `LOG`, `THROW`, or `SILENT` |
| `nplus1.detector.application-packages` | `[]` | Your app's base packages |
| `nplus1.detector.capture-stack-traces` | `true` | Disable for max performance |
| `nplus1.detector.report-format` | `CONSOLE` | `CONSOLE`, `JSON`, or `BOTH` |
| `nplus1.detector.actuator-enabled` | `true` | Expose `/actuator/nplus1` |
| `nplus1.detector.metrics-enabled` | `true` | Export Micrometer metrics |
| `nplus1.detector.max-history-size` | `1000` | Max violations in memory |
| `nplus1.detector.severity.medium` | `4` | Threshold for MEDIUM severity |
| `nplus1.detector.severity.high` | `10` | Threshold for HIGH severity |
| `nplus1.detector.severity.critical` | `50` | Threshold for CRITICAL severity |
| `nplus1.detector.exclude-paths` | actuator, swagger | URL patterns to skip |

---

## 🧪 Test Support

Add the test dependency:

```xml
<dependency>
    <groupId>io.github.nour</groupId>
    <artifactId>nplus1-detector-test</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### `@AssertNoNPlusOne` — Fail on N+1

```java
@SpringBootTest
@ExtendWith(NPlusOneDetectorExtension.class)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @AssertNoNPlusOne
    void findAllUsers_shouldNotTriggerNPlusOne() {
        List<User> users = userRepository.findAll();
        users.forEach(u -> u.getOrders().size()); // Triggers N+1 → test FAILS
    }
}
```

### `@ExpectQueries` — Assert Query Count

```java
@Test
@ExpectQueries(max = 3)
void findUsers_shouldBeEfficient() {
    userRepository.findAllWithOrders(); // JOIN FETCH → passes
}

@Test
@ExpectQueries(count = 1) // Exactly 1 query
void findById_singleQuery() {
    userRepository.findById(1L);
}
```

---

## 📊 Actuator Endpoint

```bash
# Get all violations
curl http://localhost:8080/actuator/nplus1

# Get specific violation
curl http://localhost:8080/actuator/nplus1/{id}

# Clear history
curl -X DELETE http://localhost:8080/actuator/nplus1
```

Response example:
```json
{
  "statistics": {
    "totalContextsProcessed": 150,
    "totalViolationsDetected": 12,
    "totalQueriesRecorded": 3400,
    "activeContexts": 0
  },
  "violationCount": 12,
  "violations": [
    {
      "severity": "HIGH",
      "occurrences": 25,
      "wastedTimeMs": 120,
      "codeOrigin": "com.myapp.UserService.findAll",
      "timesSeen": 8,
      "sqlSample": "select * from orders where user_id = ?"
    }
  ]
}
```

---

## 📈 Micrometer Metrics

| Metric | Type | Description |
|---|---|---|
| `nplus1.violations.total` | Counter | Total violations detected |
| `nplus1.violations.active` | Gauge | Current history size |
| `nplus1.violations.by_severity` | Counter | Violations per severity (tagged) |
| `nplus1.violations.wasted_time_ms` | Summary | Wasted time distribution |
| `nplus1.violations.occurrences` | Summary | Duplicate query count distribution |
| `nplus1.queries.redundant` | Counter | Total redundant queries |
| `nplus1.contexts.total` | Counter | Total contexts processed |
| `nplus1.contexts.clean` | Counter | Contexts with no violations |
| `nplus1.contexts.active` | Gauge | Active detection contexts |

---

## 🎯 Suppressing False Positives

```java
@Service
public class DataMigrationService {

    @SuppressNPlusOne(reason = "Intentional batch processing with manual pagination")
    public void migrateData() {
        // This method will not trigger N+1 warnings
    }
}
```

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────┐
│                Spring Boot App                    │
│                                                   │
│  ┌─────────────┐  ┌──────────────────────────┐   │
│  │  Your Code  │  │  N+1 Detector Starter    │   │
│  │             │  │                          │   │
│  │ Repository  │  │  RequestFilter           │   │
│  │ Service     │  │  → starts/ends context   │   │
│  │ Controller  │  │                          │   │
│  └──────┬──────┘  │  HibernateInspector      │   │
│         │         │  → captures SQL          │   │
│         ▼         │                          │   │
│  ┌──────────────┐ │  NPlusOneDetector        │   │
│  │  Hibernate   │ │  → analyzes patterns     │   │
│  │  JPA         │ │  → generates suggestions │   │
│  └──────────────┘ │                          │   │
│                   │  Actuator + Micrometer    │   │
│                   │  → exposes data          │   │
│                   └──────────────────────────┘   │
└──────────────────────────────────────────────────┘
```

---

## 📋 Modules

| Module | Description |
|---|---|
| `nplus1-detector-core` | Core engine — zero Spring dependencies, usable standalone |
| `nplus1-detector-spring-boot-starter` | Spring Boot auto-config, actuator, metrics, web filter |
| `nplus1-detector-test` | JUnit 5 extensions and test assertions |

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE).
