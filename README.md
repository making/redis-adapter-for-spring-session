# Redis Adapter for Spring Session

A standalone server that speaks the Redis wire protocol (RESP) and keeps what it is told in a
pluggable key-value store. A Spring application points stock
[Spring Session Data Redis](https://docs.spring.io/spring-session/reference/) at it, and its HTTP
sessions land in that store instead of in Redis.

The application keeps Spring Boot's own session auto-configuration and its ordinary Redis client,
and configures everything with properties. Only the connection target changes.

```text
  Spring Boot application                        Redis adapter server
+---------------------------+                +-----------------------------+
| spring-boot-starter-      |                | RESP codec + command layer  |
|   session-data-redis      |  RESP over TCP | pub/sub + keyspace events   |
| Lettuce                   |--------------->| authentication + TLS        |
+---------------------------+   (or TLS)     +--------------+--------------+
                                                            |
                                             KeyValueStore  |  (one SPI)
                                                            v
                                              +---------------------------+
                                              | in memory, etcd, DynamoDB,|
                                              | FoundationDB, or a store   |
                                              | you build a server around  |
                                              +---------------------------+
```

## Contents

- [When to use it](#when-to-use-it)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Using it from an application](#using-it-from-an-application)
- [Running the server](#running-the-server)
- [Backends](#backends)
- [What is implemented](#what-is-implemented)
- [Writing a backend](#writing-a-backend)
- [Example applications](#example-applications)
- [Limitations and non-goals](#limitations-and-non-goals)
- [Building from source](#building-from-source)
- [Design documents](#design-documents)

## When to use it

Use it when you want Spring Session's HTTP sessions to live in a store that is not Redis, without
that store's driver, credentials or configuration reaching the application's classpath. The
application talks Redis; where the sessions actually end up is the adapter's business, and changing
it never touches the application.

Do not use it when:

- you already run Redis and are content with it. The adapter would add a process and a network hop
  and give you nothing;
- your application is reactive (WebFlux). Only the servlet, blocking mode is supported;
- you need Redis for anything besides Spring Session. Only the commands Spring Session issues are
  implemented, and no more.

Two things are worth knowing before you start:

- **There is a server per store, and the jar you run is the choice.** The in-memory one keeps the
  sessions in the adapter process, so they are gone when it restarts and are not shared with a
  second adapter; that is the development and single-instance server, and it runs with no
  configuration at all. Running several adapters in front of the same sessions needs a store that
  is itself shared: [etcd](#etcd), [DynamoDB](#dynamodb) and
  [FoundationDB](#foundationdb) are published, and anything else is a server you assemble, which
  is [three small classes](#writing-a-backend) and no fork of this project.
- **The adapter itself holds no session state.** Everything it is asked to remember goes to the
  backend, so replicas scale as far as the backend does.

## Requirements

- Server: Java 25 or later.
- Application: nothing from this project. Any Spring Boot application that can use Spring Session
  Data Redis will do, whatever JDK it runs on.

## Quick start

### 1. Start the adapter

```bash
java -jar redis-adapter-for-spring-session-server-inmemory-<version>-exec.jar
```

It listens on port 6379, serves one database, asks for no password, and keeps the sessions in
memory. To keep them in etcd instead, run the etcd server and say where the cluster is:

```bash
java -jar redis-adapter-for-spring-session-server-etcd-<version>-exec.jar \
    --redis-adapter.etcd.endpoints=http://etcd-0:2379
```

Nothing else differs between the two, and nothing about the application changes.

### 2. Give the application Spring Session

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-session-data-redis</artifactId>
</dependency>
```

### 3. Point it at the adapter

<!-- snippet:app-connection -->
```properties
spring.data.redis.host=adapter.example.com
spring.data.redis.port=6379
```

That is the whole integration. Spring Boot configures Spring Session as soon as the starter is on
the class path, so the application needs no configuration class and no annotation of its own — and
still nothing from this project. Its sessions are now written to whatever backend the adapter was
started with.

## Using it from an application

Everything below is a Spring Boot property. The
[session properties](https://docs.spring.io/spring-boot/reference/web/spring-session.html) are
Spring Boot's own and behave as they do against Redis; only their effect on the adapter is
described here.

### Simple mode

The default. Each session is one hash whose expiry Spring Session sets itself. Nothing is announced
and nothing is indexed: a session that has expired is simply no longer there the next time it is
asked for. Use it when the application only needs to read and write sessions.

### Indexed mode

Indexed mode adds what Spring Session cannot do with a hash alone: session events, a lookup by
principal name, and a background job that reaps sessions nobody came back to.

<!-- snippet:app-indexed -->
```properties
spring.session.data.redis.repository-type=indexed
```

Leave `spring.session.data.redis.configure-action` alone. Its default asks the server to turn
keyspace notifications on at start-up, and the adapter answers that the way Redis would, because it
emits those notifications regardless. Setting it to `none` — the equivalent of declaring
`ConfigureRedisAction.NO_OP` — is not needed.

Session events are published as ordinary application events:

<!-- snippet:session-event-listener -->
```java
@Component
public class SessionEventListener {

    private static final Logger logger = LoggerFactory.getLogger(SessionEventListener.class);

    @EventListener
    public void onSessionCreated(SessionCreatedEvent event) {
        logger.info("session {} created", event.getSessionId());
    }

    @EventListener
    public void onSessionExpired(SessionExpiredEvent event) {
        logger.info("session {} expired", event.getSessionId());
    }

    @EventListener
    public void onSessionDeleted(SessionDeletedEvent event) {
        logger.info("session {} deleted", event.getSessionId());
    }

}
```

`SessionExpiredEvent` arrives because the backend reports the key it dropped, not because anything
polled for it. How long that takes is the backend's business: the in-memory one sweeps once a second
by default, and etcd removes the key when its lease runs out, so either way the event follows the
expiry within about a second.

Sessions can be looked up by the user they belong to:

<!-- snippet:find-by-index-name -->
```java
@Service
public class ActiveUserSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public ActiveUserSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    public Set<String> sessionIdsOf(String username) {
        return this.sessions.findByPrincipalName(username).keySet();
    }

}
```

The index is filled from the session's principal, which Spring Security sets, or which the
application sets itself under
`FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME`.

### Expiration stores

Indexed mode records when each session is due to expire. By default that record is a set per minute
(`spring:session:expirations:<minute>`), which the cleanup job reads on the minute. An application
that would rather keep one sorted set of every live session declares Spring Session's alternative
store:

<!-- snippet:sorted-set-expiration-config -->
```java
@Configuration
public class SortedSetExpirationConfig {

    @Bean
    public RedisSessionExpirationStore sortedSetRedisSessionExpirationStore(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisOperations = new RedisTemplate<>();
        redisOperations.setKeySerializer(RedisSerializer.string());
        redisOperations.setHashKeySerializer(RedisSerializer.string());
        redisOperations.setConnectionFactory(connectionFactory);
        redisOperations.afterPropertiesSet();
        return new SortedSetRedisSessionExpirationStore(redisOperations,
                RedisIndexedSessionRepository.DEFAULT_NAMESPACE);
    }

}
```

Both stores work against the adapter. Nothing else about it changes: a session's death is still
announced by its shadow key expiring.

### Namespace and database

Both behave as they do against Redis.

<!-- snippet:app-namespace -->
```properties
spring.session.data.redis.namespace=acme:web
spring.data.redis.database=1
```

The database has to exist on the adapter as well, so `redis-adapter.databases` must be at least one
greater than the index the application selects — see the
[configuration reference](#configuration-reference). Nothing else changes: the keyspace
notifications the adapter emits name the database they happened in, so events still arrive.

### Authentication

Give the server a password and clients authenticate exactly as they do against Redis:

<!-- snippet:app-password -->
```properties
spring.data.redis.password=s3cret
```

Until a connection has authenticated the adapter answers everything but `AUTH`, `HELLO` and `QUIT`
with `NOAUTH Authentication required.`. The password crosses the network in clear text, as it does
with Redis, so a protected server on an untrusted network wants TLS as well.

### TLS

When the server serves TLS, the application connects to it the ordinary Spring Boot way, over an
[SSL bundle](https://docs.spring.io/spring-boot/reference/features/ssl.html):

<!-- snippet:app-tls -->
```properties
spring.data.redis.ssl.enabled=true
spring.data.redis.ssl.bundle=adapter
spring.ssl.bundle.pem.adapter.truststore.certificate=file:/etc/app/tls/ca.crt
```

The server side of the same connection is [below](#tls-1).

## Running the server

```bash
java -jar redis-adapter-for-spring-session-server-<backend>-<version>-exec.jar
```

Which store the sessions land in is which jar this is; the published ones are `inmemory`, `etcd`,
`dynamodb` and `foundationdb`, and they are listed under [Backends](#backends). Everything else on this page is
the same whichever one you run.

### Configuration reference

Every setting is an ordinary Spring Boot property, so a command line argument, an
`application.properties` beside the jar, or an environment variable all work.

<!-- properties:redis-adapter -->

| Property | Default | What it does |
| --- | --- | --- |
| `redis-adapter.bind-address` | `0.0.0.0` | The address to listen on. The default accepts on every interface, which is what a server in a container wants. |
| `redis-adapter.port` | `6379` | The port to listen on. `0` binds an ephemeral one. |
| `redis-adapter.password` | none | The password clients must authenticate with. Unset, anything that reaches the port can read and write every session. |
| `redis-adapter.databases` | `1` | How many numbered databases to serve, each an independent keyspace. |
| `redis-adapter.shutdown-timeout` | `10s` | How long a connection still running a command is given before it is interrupted on shutdown. |
| `redis-adapter.ssl.enabled` | unset | Whether to serve TLS. Unset means TLS exactly when a bundle is named; `false` keeps a configured bundle unused. |
| `redis-adapter.ssl.bundle` | none | The `spring.ssl.bundle.*` holding the server's certificate and key. |
| `redis-adapter.ssl.client-auth` | `none` | Whether clients must present a certificate of their own: `none`, `want` or `need`. |

Each server adds the settings of the store it was built around, under
`redis-adapter.<backend>`; they are listed with that backend, under [Backends](#backends).

Written as properties, the settings an operator is most likely to change look like this:

<!-- snippet:server-settings -->
```properties
redis-adapter.bind-address=0.0.0.0
redis-adapter.port=6379
redis-adapter.databases=1
redis-adapter.password=s3cret
```

The same settings as environment variables, which is how a container platform usually hands them
over:

<!-- snippet:server-env -->
```properties
REDIS_ADAPTER_PORT=16379
REDIS_ADAPTER_PASSWORD=s3cret
REDIS_ADAPTER_DATABASES=16
```

### TLS

The server's certificate and key come from an SSL bundle as well, and
`redis-adapter.ssl.bundle` names it:

<!-- snippet:server-tls -->
```properties
redis-adapter.ssl.bundle=adapter
redis-adapter.ssl.client-auth=none
spring.ssl.bundle.pem.adapter.keystore.certificate=file:/etc/adapter/tls/tls.crt
spring.ssl.bundle.pem.adapter.keystore.private-key=file:/etc/adapter/tls/tls.key
spring.ssl.bundle.pem.adapter.reload-on-update=true
```

Naming a bundle is enough to serve TLS; clients then connect over `rediss://`. Setting
`redis-adapter.ssl.enabled=true` without a bundle is refused as the properties bind, rather than
leaving a port serving in clear text under a setting that says otherwise. `client-auth=need` turns
the client's certificate into its credential (mutual TLS).

`reload-on-update=true` is the whole of certificate rotation. When the issuer replaces the files on
disk, Spring Boot rebuilds the bundle and the adapter serves the new certificate to every client
that connects afterwards. Nothing restarts, and the connections already open run to their end on
the certificate they were given. A rotation that cannot be read leaves the previous material
serving and is logged at ERROR.

### Health and metrics

The application also serves the Spring Boot actuator over HTTP, on the usual `server.port` (that
port carries nothing but the actuator; sessions are served over RESP on `redis-adapter.port`).

- `GET /actuator/health` reports the adapter as up only while it is accepting connections, with the
  port, the number of databases and how many connections are open. A load balancer can therefore
  take an instance out before its connections are dropped. Whether the *backend* is reachable is
  not asked here: a backend that can be unreachable contributes a health indicator of its own.
- `redis.adapter.connections.active` and `redis.adapter.connections.accepted` are published to
  whatever meter registry is on the classpath.

### Running more than one

The adapter keeps no session state, so replicas behind a load balancer serve the same sessions —
but only as far as the backend does. The in-memory server does not, since each replica owns its
own map. Scaling out means a server built around a store that is shared, and one that can tell a
replica about a key another replica expired, because that is what an application's
`SessionExpiredEvent` is made of. [etcd](#etcd), [DynamoDB](#dynamodb) and
[FoundationDB](#foundationdb) do all three.

## Backends

Each store gets a server of its own, `redis-adapter-for-spring-session-server-<backend>`, and the
jar you run is the whole of the choice — there is no property to set and nothing to select. Four
are published, and nothing about the application changes between them. A store this project does
not ship gets a server of your own; see [Writing a backend](#writing-a-backend).

### In-memory

`redis-adapter-for-spring-session-server-inmemory`, which runs with no configuration at all. Each
adapter owns its own map, so the sessions are gone when it restarts and a second adapter serves
different ones. Keys nobody comes back to are removed by a sweeper.

<!-- properties:redis-adapter.in-memory -->

| Property | Default | What it does |
| --- | --- | --- |
| `redis-adapter.in-memory.sweeper-enabled` | `true` | Whether the in-memory backend sweeps for keys whose time has passed but which nobody has touched. |
| `redis-adapter.in-memory.sweep-interval` | `1s` | How long between sweeps, which is the longest an expired key can sit there unnoticed. |

<!-- snippet:server-in-memory -->
```properties
redis-adapter.in-memory.sweeper-enabled=true
redis-adapter.in-memory.sweep-interval=1s
```

### etcd

`redis-adapter-for-spring-session-server-etcd`. Sessions live in an [etcd](https://etcd.io)
cluster, so they are shared by every adapter pointed at it and outlive all of them. This is the
server for running more than one adapter.

<!-- properties:redis-adapter.etcd -->

| Property | Default | What it does |
| --- | --- | --- |
| `redis-adapter.etcd.endpoints` | `http://localhost:2379` | The etcd cluster's client URLs. Requests go to the one that last worked and move on when a member cannot be reached. |
| `redis-adapter.etcd.key-prefix` | `/redis-adapter/` | Where in etcd's keyspace the sessions live. Each database gets `<key-prefix><database>/` of its own. |
| `redis-adapter.etcd.connect-timeout` | `5s` | How long to wait for a connection to an endpoint. |
| `redis-adapter.etcd.request-timeout` | `5s` | How long to wait for etcd to answer, which bounds how long a Redis command can hang. |
| `redis-adapter.etcd.watch-retry-delay` | `1s` | How long before the watch that delivers session events is opened again after it fails. |
| `redis-adapter.etcd.username` | none | The etcd user, for a cluster with authentication enabled. |
| `redis-adapter.etcd.password` | none | That user's password. |
| `redis-adapter.etcd.ssl-bundle` | none | The `spring.ssl.bundle.*` to reach an `https://` etcd with, and the client certificate for mutual TLS. |

<!-- snippet:server-etcd -->
```properties
redis-adapter.etcd.endpoints=http://etcd-0:2379,http://etcd-1:2379,http://etcd-2:2379
redis-adapter.etcd.key-prefix=/redis-adapter/
```

The same as environment variables, a list of endpoints included:

<!-- snippet:server-etcd-env -->
```properties
REDIS_ADAPTER_ETCD_ENDPOINTS=http://etcd-0:2379,http://etcd-1:2379
REDIS_ADAPTER_ETCD_KEY_PREFIX=/redis-adapter/
```

Three things are worth knowing:

- **Expiry is etcd's own.** A key with a TTL is attached to an etcd lease, so a session nobody
  comes back to is removed by etcd rather than by anything the adapter polls for. Leases are whole
  seconds and are rounded up, so a key is collected shortly *after* it is due; it is already gone
  as far as every read is concerned, because the exact deadline travels with the value.
- **Events cross the adapters.** Every adapter watches its keyspace, so a session that expires or
  is deleted anywhere is announced to the clients subscribed everywhere. An application connected
  to one adapter therefore hears about a session another adapter removed, which is what
  `SessionExpiredEvent` needs and what the in-memory server cannot do.
- **The keyspace is yours to choose.** `key-prefix` is where the sessions live, and each database
  gets a keyspace of its own underneath it. Two deployments can share a cluster by taking different
  prefixes; a cluster used for other things is untouched outside them.

It reaches etcd over the HTTP gateway etcd serves on its client port (`--enable-grpc-gateway`, on
by default), which is why no gRPC stack, protobuf or Netty is added to the server. Authentication
is `redis-adapter.etcd.username` / `password`, and an `https://` endpoint is reached through an SSL
bundle named by `redis-adapter.etcd.ssl-bundle` — the same bundles the adapter's own port uses,
including a client certificate for mutual TLS.

#### What it costs

etcd keeps every value in memory, replicates it to every member and commits every write to disk,
so a session write costs more here than it does against Redis and it is worth knowing how much
before planning around it. Measured against a single-member etcd in a container whose commits took
3 ms; `.docs/design/etcd-performance.md` has the full numbers and the harness that takes them, so
you can take your own.

- **A session write costs about ten milliseconds and six etcd raft writes**, a read about half
  a millisecond and one read call. Sessions written per second come out at roughly the cluster's
  raft write rate divided by six; reads barely enter into it.
- **One client connection carries 30 to 50 session writes per second.** Commands on a connection
  are served in order, as Redis serves them, so an application sharing one Lettuce connection
  queues behind itself. More application instances, or more connections, multiply it.
- **Keep session attributes in the tens of kilobytes.** Below that the raft commit dominates and
  the bytes are noise — a 100 KB session costs a few milliseconds more than a 1 KB one. Above
  etcd's `--max-request-bytes` (1.5 MiB by default) the write is refused outright: the client is
  told `ERR value too large for the backend`, which is the adapter saying the session did not
  fit and that repeating the write will not help, and etcd's own reason is in the adapter's log.
  Nothing is written, so the application sees the save fail rather than a session it cannot read
  back. A cluster also has a total size limit (`--quota-backend-bytes`, 2 GiB by default).
- **One key is contended by design**, and that is handled: every session expiring in the same
  minute joins that minute's set, so the adapter applies the writes waiting for one key together,
  in a single etcd transaction, instead of letting them compete. Hundreds of writers at once
  therefore cost *less* per write rather than more (0.01 etcd calls per write at 256 writers,
  against 15 without it). What still grows is the bucket itself: adding to a minute that already
  holds 10,000 sessions costs about five times what an empty one does, however few writers there
  are.

### DynamoDB

`redis-adapter-for-spring-session-server-dynamodb`. Sessions live in one DynamoDB table, so they
are shared by every adapter pointed at it and outlive all of them — with nothing of your own to
operate underneath: no cluster, no compaction, no upgrades. The table is created when it is absent
(on-demand billing), so on AWS the server runs with no configuration beyond a region.

Where DynamoDB is and how requests are signed are [Spring Cloud
AWS](https://awspring.io/)'s ordinary `spring.cloud.aws.*` properties — the default credential
provider chain, a static access key, or an endpoint override that points a local run at an
emulator. The backend adds only its own tuning:

<!-- properties:redis-adapter.dynamodb -->

| Property | Default | What it does |
| --- | --- | --- |
| `redis-adapter.dynamodb.table-name` | `redis-adapter` | The table the sessions live in, created when absent unless `create-table` says otherwise. |
| `redis-adapter.dynamodb.create-table` | `true` | Whether to create the table (and its index and TTL setting) when it is absent. Off for deployments whose tables are provisioned elsewhere. |
| `redis-adapter.dynamodb.shards` | `4` | How many partitions a set's members and the deadline index spread over. DynamoDB caps one partition at 1,000 writes/s, and every session expiring in the same minute joins one bucket, so this is that bucket's ceiling in thousands of writes per second. Fixed for the life of a table. |
| `redis-adapter.dynamodb.poll-interval` | `100ms` | How often the key-event log is polled. Half of how long a session event takes to arrive — and a standing charge, because an idle poll is a billed read. |
| `redis-adapter.dynamodb.cursor-lag` | `500ms` | How far the log cursor stays behind wall-clock. It must outlast the replicas' clock skew plus a write's latency, or a late-stamped event is lost; it is the other half of an event's arrival time. |
| `redis-adapter.dynamodb.sweep-interval` | `1s` | How long between sweeps for sessions nobody comes back to, which is the longest an abandoned session can sit unannounced. |
| `redis-adapter.dynamodb.log-retention` | `60s` | How long read key-event log entries are kept before the sweeper trims them. Must comfortably outlast `cursor-lag`. |
| `redis-adapter.dynamodb.request-timeout` | `5s` | How long to wait for DynamoDB to answer one request, which bounds how long a Redis command can hang. |
| `redis-adapter.dynamodb.max-attempts` | `10` | How many times an operation retries a key that changed underneath it, or a request DynamoDB throttled, before giving up. |

<!-- snippet:server-dynamodb -->
```properties
spring.cloud.aws.region.static=ap-northeast-1
redis-adapter.dynamodb.table-name=redis-adapter
redis-adapter.dynamodb.shards=4
```

The same as environment variables:

<!-- snippet:server-dynamodb-env -->
```properties
SPRING_CLOUD_AWS_REGION_STATIC=ap-northeast-1
REDIS_ADAPTER_DYNAMODB_TABLE_NAME=redis-adapter
REDIS_ADAPTER_DYNAMODB_SHARDS=4
```

Three things are worth knowing:

- **Expiry is the adapter's, not DynamoDB's.** AWS's own TTL deletes an expired item within 48
  hours, best effort, which is useless for announcing a session's death. The exact deadline lives
  on the item and every read honours it; a sweeper — one replica per database, elected by a lease —
  removes and announces the sessions nobody comes back to, and DynamoDB's TTL is written only so
  that storage a sweeper never reached is eventually reclaimed.
- **Events cross the adapters through a log the removal writes atomically.** A removal and its
  announcement are one transaction, so they cannot part company, and every adapter polls the log —
  which is what carries a `SessionExpiredEvent` to an application connected to a different replica.
  How fast is `poll-interval` plus `cursor-lag`, about 0.6 s with the defaults.
- **IAM.** The adapter needs the item operations on its table and index (`GetItem`, `Query`,
  `PutItem`, `UpdateItem`, `DeleteItem`, `BatchGetItem`, `BatchWriteItem`, `TransactWriteItems`)
  plus `DescribeTable`, and — unless the table is provisioned elsewhere and `create-table` is off —
  `CreateTable` and `UpdateTimeToLive`.

#### What it costs

Alone among the backends, this one is billed per request, so the price is part of the design and
worth knowing before planning around it (on-demand, us-east-1 rates):

- **A session write is a transaction, and DynamoDB bills a transactional write at twice a plain
  one.** That is the price of the removal and its announcement being atomic, and of a multi-item
  save being all-or-nothing. An indexed-mode session save is about eight requests; at $0.625 per
  million plain writes, a million session saves land in the low tens of dollars.
- **The poll is a standing charge.** One replica polling one database every 100 ms is roughly 0.9
  million strongly consistent reads a day — about $3.30 per replica and database per month while
  nothing happens at all. The sweep adds a lease write and an index read per interval. Both scale
  with replicas × databases, not with traffic; widen the intervals if events may arrive later.
- **One item tops out at 400 KB**, so a session attribute has to fit in that; a bigger one is
  refused with `ERR value too large for the backend`, before anything is written. Collections
  (the principal index, the expiration buckets) store one item per member and have no such limit.

The tests run against an emulator, because AWS publishes no DynamoDB you can run;
`.docs/design/architecture.md` §12.6 records exactly what that does and does not prove, and the
store module carries an opt-in suite against a real table for the difference.

### FoundationDB

`redis-adapter-for-spring-session-server-foundationdb`. Sessions live in a
[FoundationDB](https://www.foundationdb.org/) cluster, so they are shared by every adapter
pointed at it and outlive all of them. What it has that the other shared backends do not is real
multi-key transactions: a session save is one commit, and a session's removal and the
announcement of it are written together or not at all.

**One thing has to be installed.** FoundationDB serves no HTTP API, so the only way to reach it
is its native client, `libfdb_c` — which is not in any jar. Install the FoundationDB client
package matching your cluster's version on the machine or in the image that runs the server. It
is the one backend here with a prerequisite outside the jar.

The cluster is not named by a URL, either: a FoundationDB client reads a **cluster file** and
finds the coordinators from it. Name the file, or hand the server its contents and let it write
one.

<!-- properties:redis-adapter.foundationdb -->

| Property | Default | What it does |
| --- | --- | --- |
| `redis-adapter.foundationdb.cluster-file` | none | The path of the cluster file. Leaving both this and `cluster-file-contents` unset leaves the client to look where it always looks (`FDB_CLUSTER_FILE`, then `/etc/foundationdb/fdb.cluster`). |
| `redis-adapter.foundationdb.cluster-file-contents` | none | The contents of a cluster file to write and use, for a deployment that delivers configuration rather than files. Mutually exclusive with `cluster-file`. |
| `redis-adapter.foundationdb.api-version` | `730` | The FoundationDB API version to speak. It may be selected only once per JVM, and the installed native client has to support it. |
| `redis-adapter.foundationdb.key-prefix` | `/redis-adapter/` | Where in the cluster's keyspace the sessions live. Each database gets a keyspace of its own underneath it. |
| `redis-adapter.foundationdb.transaction-timeout` | `5s` | How long one transaction may take, which bounds how long a Redis command can hang. A read against a cluster that is not there waits for ever without it. |
| `redis-adapter.foundationdb.watch-timeout` | `5s` | How long one watch on the key-event counter lives before it is renewed. Events arrive as soon as the watch fires; this bounds the wait when a watch is lost. |
| `redis-adapter.foundationdb.sweep-interval` | `1s` | How long between sweeps for sessions nobody comes back to, which is the longest an abandoned session can sit unannounced. |
| `redis-adapter.foundationdb.log-retention` | `60s` | How long key-event log entries are kept before the sweeper trims them. A replica away for longer than this loses the events in between, so it is a correctness setting. |
| `redis-adapter.foundationdb.retry-delay` | `1s` | How long before the follower that delivers session events is started again after it fails. |
| `redis-adapter.foundationdb.max-attempts` | `10` | How many times FoundationDB retries a transaction that conflicted before giving up. |

<!-- snippet:server-foundationdb -->
```properties
redis-adapter.foundationdb.cluster-file=/etc/foundationdb/fdb.cluster
redis-adapter.foundationdb.key-prefix=/redis-adapter/
```

The same as environment variables, where there is no file to name so the contents travel instead:

<!-- snippet:server-foundationdb-env -->
```properties
REDIS_ADAPTER_FOUNDATIONDB_CLUSTER_FILE_CONTENTS=redis:adapter@fdb-0:4500,fdb-1:4500,fdb-2:4500
REDIS_ADAPTER_FOUNDATIONDB_KEY_PREFIX=/redis-adapter/
```

Three things are worth knowing:

- **Expiry is entirely the adapter's.** FoundationDB has no TTL, no lease and nothing resembling
  one. The exact deadline lives on the key and every read honours it; a sweeper — one replica per
  database, elected by a lease — removes and announces the sessions nobody comes back to, working
  from a deadline-ordered index that the same transaction as the deadline keeps in step.
- **Events cross the adapters through a log the removal writes atomically.** A removal and its
  announcement are one transaction, so they cannot part company, and every adapter follows the
  log — which is what carries a `SessionExpiredEvent` to an application connected to a different
  replica. The log is keyed by FoundationDB's own commit version rather than by a clock, so
  events arrive in one order everybody agrees on and the replicas' clock skew does not enter into
  it. A watch wakes each adapter, so an idle fleet costs nothing.
- **A session attribute has to fit in 100,000 bytes.** That is FoundationDB's ceiling on one
  value, and it is the one place this backend is tighter than the others. It binds on an
  attribute rather than on the session, because a hash keeps one key per field; a bigger one is
  refused with `ERR value too large for the backend`, before anything is written. Collections
  (the principal index, the expiration buckets) keep one key per member and have no such limit.

#### What it costs

- **A session save is one commit**, not six raft writes as on etcd and not eight billed requests
  as on DynamoDB, because the whole save is one transaction. The unit to plan in is therefore the
  cluster's commit rate.
- **Contention on one key degrades instead of failing**, and the ordinary contended case does not
  arise at all: every session expiring in the same minute joins that minute's set, and with one
  key per member those writers are not writing the same key. Nothing conflicts and nothing is
  lost.
- **The standing charge is one watch per database, not a poll.** An idle replica wakes only when
  something is removed. The sweeper's interval is the only recurring work, and it falls on the one
  elected holder.

`.docs/design/architecture.md` §13 is the design, including the layout the 100,000-byte ceiling
forces and what the tests do about a native library that is not in the jar.

## What is implemented

What Spring Session issues, the handshake a client needs to get that far, and `SET` / `GET`, which
it never sends and which are there so that the server can be tried out by hand.

<!-- commands -->

| Commands | Used for |
| --- | --- |
| `PING`, `HELLO`, `AUTH`, `CLIENT`, `SELECT`, `QUIT`, `COMMAND`, `CONFIG` | The client handshake. `CONFIG GET`/`SET notify-keyspace-events` is answered so that indexed mode's start-up check passes. |
| `HGETALL`, `HSET`, `HMSET`, `HGET` | The session itself, which is one hash per session. |
| `EXISTS`, `DEL`, `UNLINK`, `RENAME`, `TYPE` | Reading, deleting and changing the id of a session. |
| `EXPIRE`, `PEXPIRE`, `EXPIREAT`, `PEXPIREAT`, `PERSIST`, `TTL`, `PTTL` | Session expiry. |
| `APPEND` | The shadow key of indexed mode, an empty string whose expiry announces the session's death. |
| `SADD`, `SREM`, `SMEMBERS` | The principal index and the expiration buckets. |
| `ZADD`, `ZREM`, `ZREVRANGEBYSCORE` | The optional sorted-set expiration store. |
| `PUBLISH`, `SUBSCRIBE`, `UNSUBSCRIBE`, `PSUBSCRIBE`, `PUNSUBSCRIBE` | Session events, together with the `__keyevent@<db>__:del` and `__keyevent@<db>__:expired` notifications the adapter emits when a key goes. |
| `SET`, `GET` | Nothing Spring Session does — a `redis-cli` pointed at the server, so that a backend can be tried out by hand rather than only through an application. |

`SET` takes the four expiry options — `EX`, `PX`, `EXAT` and `PXAT` — and no others. All four
become one absolute deadline the backend writes together with the value, in a single operation: a
backend spread over several nodes could not honour a deadline by following the write with a second
round trip, because a process that dies between the two leaves behind a key that never expires.
`NX`, `XX`, `KEEPTTL` and `GET` are conditional writes the backend SPI does not express, and are
answered `ERR syntax error` rather than quietly given semantics they do not have. A `SET` with no
expiry option replaces the key whatever it held and drops the deadline it had, as Redis does;
`EXPIRE` and `PEXPIRE` put a new one on a key that is already there:

```bash
redis-cli -p 6379 SET demo hello EX 10
redis-cli -p 6379 TTL demo
redis-cli -p 6379 GET demo
```

Values are opaque. Only key names and hash-field names are text; everything else is stored and
returned byte for byte, whichever serializer the application uses.

There is no `SCAN`, `KEYS`, `MULTI`/`EXEC`, `EVAL`, `INFO`-driven feature, cluster slot,
replication or persistence. Spring Session's session path uses none of them.
`.docs/design/redis-command-surface.md` has the exact semantics of each command above.

## Writing a backend

A backend implements one interface, `am.ik.redis.adapter.store.KeyValueStore`, and depends on the
core module only. It never sees RESP, connections or Spring.

<!-- methods:KeyValueStore -->

| Method | Contract |
| --- | --- |
| `currentTimeMillis()` | The store's clock. The command layer converts relative TTLs against it, so expiry stays consistent. |
| `get(byte[])` | The typed value, or `null` if the key is absent or has expired. |
| `exists(byte[])` | Whether the key is there, honouring expiry. |
| `set(byte[], byte[], Long)` | Replaces the key with a string, whatever it held, giving it the deadline asked for — written with the value, not after it — and announcing nothing. |
| `append(byte[], byte[])` | Appends to a string, creating it if absent — appending nothing materializes an empty one. |
| `hset(byte[], Map)` | Sets hash fields, creating the hash if absent. |
| `sadd(byte[], List)`, `srem(byte[], List)` | Adds to and removes from a set; an emptied set removes the key. |
| `zadd(byte[], Map)`, `zrem(byte[], List)` | The same for a sorted set, a member that is already there moving to its new score. |
| `delete(byte[])` | Deletes a key, firing `onDeleted` if it was alive. |
| `rename(byte[], byte[])` | Moves a value and its TTL, firing no key event — a rename must not look like a delete. |
| `expireAt(byte[], long)`, `persist(byte[])`, `getExpireAt(byte[])` | The absolute per-key TTL, in epoch milliseconds. |
| `addKeyEventListener(KeyEventListener)` | Registers the listener the adapter turns into keyspace notifications. |
| `close()` | Releases whatever the store holds; must be idempotent. |

Five rules matter more than the signatures:

- **Expiry is the store's job, and it must be announced.** Every operation honours passive
  expiration: touching a key whose deadline has passed removes it, fires
  `KeyEventListener.onExpired` and then treats it as absent. A store should also expire keys nobody
  touches, or a session that is simply abandoned — the common case — is never announced as expired
  and the application never fires `SessionExpiredEvent`.
- **Events are fired at the moment of removal**, synchronously, never dropped. Spring Session's
  `SessionDeletedEvent` and `SessionExpiredEvent` are made of nothing else.
- **Bytes are opaque.** Keys, field names and members are compared by value (`ByteArrayKey` is
  there for that); payload bytes are returned exactly as they arrived and are never parsed.
- **Two failures have names of their own.** `TypeMismatchException` says the key holds another
  kind of value, and `ValueTooLargeException` says the value is bigger than this backend will
  take; the command layer answers them with `WRONGTYPE` and `ERR value too large for the backend`
  respectively. Both tell the client something it can act on, which is why they are not left to
  the generic path — anything else a backend throws becomes `ERR internal error` and is logged
  with its stack trace. A backend with no size limit never throws the second one.
- **A distributed backend has to carry the events across nodes.** If an adapter replica other than
  the one holding a client's subscription expires a key, that expiry still has to reach the
  subscriber. `KeyEventListener` is the seam where a backend plugs in its own watch or notify
  channel. For a single-node backend it is local and trivial.

Implementations must be safe for concurrent use: the server runs one virtual thread per
connection.

A server is built around exactly one backend, and what a backend contributes to it is one bean, a
`KeyValueStoreFactory`, which is asked for a store per database:

<!-- snippet:backend-factory -->
```java
public class MyKeyValueStoreFactory implements KeyValueStoreFactory {

    @Override
    public String name() {
        return "my-backend";
    }

    @Override
    public KeyValueStore create(int databaseIndex) {
        return new MyKeyValueStore(databaseIndex);
    }

}
```

<!-- snippet:backend-registration -->
```java
@Configuration(proxyBeanMethods = false)
public class MyBackendConfiguration {

    @Bean
    public MyKeyValueStoreFactory myKeyValueStoreFactory() {
        return new MyKeyValueStoreFactory();
    }

}
```

Then build a server around it: a Maven module depending on
`redis-adapter-for-spring-session-server`, holding that configuration class and a main class.

<!-- snippet:backend-application -->
```java
@SpringBootApplication
public class MyRedisAdapterServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyRedisAdapterServerApplication.class, args);
    }

}
```

That is the whole of it. The server module auto-configures the port, the lifecycle, the actuator
and everything on this page; it never knows what it is writing to, and it refuses to start with
any number of backends other than one. Nothing in this project is forked, patched or rebuilt — and
because the module is yours, a store whose driver cannot be published, or whose licence will not
allow it, is served by a server that lives in your own repository.

Two details are easy to miss. Each database is an independent keyspace, so two calls to `create`
must return stores that share no keys — a shared backend separates them by the index it is given.
And the factory is built while the application is still starting and asked for its stores
afterwards, so it must hold no resource and open no connection until `create` is called.

`redis-adapter-for-spring-session-server-inmemory` is the smallest complete example of all this,
and `am.ik.redis.adapter.inmemory.InMemoryKeyValueStore` is the reference store, depending on the
core exactly as an external backend does. `am.ik.redis.adapter.etcd.EtcdKeyValueStore` is the
worked example of the harder half: how a shared backend keeps read-modify-write atomic across
replicas, and how it carries key events between them.

The server module also publishes its tests as a `test-jar`. It holds the harness the backends here
are proven with — the shipped configuration under a real Lettuce client running stock Spring
Session, the key names Spring Session writes, and the benchmark — so a backend of your own can be
held to the same standard as the ones in this repository.

## Example applications

[`examples/session-example-etcd`](examples/session-example-etcd) is a Spring Boot application that
keeps its sessions in etcd through the adapter, generated from start.spring.io and left as ordinary
as possible: the session starter on the class path, a host and a port in `application.properties`,
and one screen that remembers who you are.

```bash
./mvnw install -DskipTests   # publish the adapter jar to the local repository
cd examples/session-example-etcd
./mvnw spring-boot:test-run  # starts etcd, an adapter, and the application on :8080
```

Its end-to-end tests drive a browser against two instances of the application, and a Spring profile
decides only where the sessions go: `./mvnw test` runs them against two adapters sharing one etcd,
and `./mvnw test -Dspring.profiles.active=redis` runs the same assertions against a real Redis.
Redis is the oracle, so the two runs are expected to agree — including that a session created
against one instance is served by the other.
See [its README](examples/session-example-etcd/README.md).

[`examples/session-example-dynamodb`](examples/session-example-dynamodb) is the same application
over the DynamoDB server, its adapters sharing one emulated DynamoDB (Floci) — see
[its README](examples/session-example-dynamodb/README.md).

[`examples/session-example-foundationdb`](examples/session-example-foundationdb) is the same
application again, its adapters sharing one FoundationDB. It is also the one place that shows what
[the native client](#foundationdb) means in practice: the adapter runs on a JRE image with
`libfdb_c` copied into it out of the FoundationDB image the cluster itself runs, and the
application still links against nothing. See
[its README](examples/session-example-foundationdb/README.md).

## Limitations and non-goals

- Reactive / WebFlux is out of scope. Only the servlet session repositories are tested against the
  adapter.
- Redis Cluster, Sentinel, replication and persistence are not implemented, and neither is any
  command Spring Session does not use.
- Only as much of RESP3 as Lettuce needs to complete its handshake and run the session commands.
- The in-memory server is single-node: sessions do not survive a restart and are not shared
  between adapter replicas. Sharing them means [etcd](#etcd) or a server of your own.
- The etcd backend inherits etcd's shape: values are kept in memory and replicated to every member,
  so it suits sessions rather than large payloads, and a cluster has a total size limit. What it
  costs is measured in [What it costs](#what-it-costs) rather than left to be discovered.

## Building from source

Java 25 or later.

```bash
./mvnw clean spring-javaformat:apply test
```

The etcd backend's tests start a real etcd in a container, the DynamoDB backend's start the Floci
emulator in one, and the FoundationDB backend's start a real FoundationDB, so a Docker (or
compatible) daemon has to be running for the full build. The FoundationDB tests also fetch the
native client the driver needs — it is not in any jar — the first time they run on a machine, and
say so while they do it; it is cached outside `target/`, so a clean build does not fetch it
again.

The performance harness is not part of that build — it measures rather than asserts, and it takes
minutes. Run it on its own:

```bash
./mvnw test -Pperformance
```

Each server module measures its own backend, at the SPI and through a real client, and writes its
tables to that module's `target/performance/`. `.docs/design/etcd-performance.md` is one run of it,
written up: the etcd numbers are read against the in-memory ones, which are the same cases with the
network taken out.

The build has ten modules, in two layers — a store, and the server built around it:

| Module | What it is |
| --- | --- |
| `redis-adapter-for-spring-session-core` | The `KeyValueStore` SPI and the protocol, command, pub/sub and server layers. Depends on `slf4j-api` and `jspecify` and nothing else, and contains no backend. |
| `redis-adapter-for-spring-session-inmemory` | The in-memory backend. Depends on the core only, exactly as an external backend would. |
| `redis-adapter-for-spring-session-etcd` | The etcd backend. Also depends on the core only: it talks to etcd's HTTP gateway with the JDK's own client, so no gRPC stack is added to the server. Its tests run against a real etcd in a container. |
| `redis-adapter-for-spring-session-dynamodb` | The DynamoDB backend. Depends on the core and the AWS SDK's `dynamodb` client over the JDK's own HTTP connection — no Netty, no Jackson. Its tests run against the Floci emulator in a container, with an opt-in suite for a real table. |
| `redis-adapter-for-spring-session-foundationdb` | The FoundationDB backend. Depends on the core and on `fdb-java`, which is one jar with no transitive dependencies but which needs the native `libfdb_c` installed beside it — FoundationDB serves no HTTP API, so unlike etcd there is nothing to choose. Its tests run against a real FoundationDB in a container. |
| `redis-adapter-for-spring-session-server` | Everything the Spring Boot server is except the backend: the properties, the lifecycle, the actuator, TLS. Holds the compatibility tests that drive it through a real Lettuce client running stock Spring Session, and publishes them as a `test-jar` for the servers built on it. |
| `redis-adapter-for-spring-session-server-inmemory` | The runnable server around the in-memory backend. |
| `redis-adapter-for-spring-session-server-etcd` | The runnable server around the etcd backend. |
| `redis-adapter-for-spring-session-server-dynamodb` | The runnable server around the DynamoDB backend, its client configured through Spring Cloud AWS. |
| `redis-adapter-for-spring-session-server-foundationdb` | The runnable server around the FoundationDB backend. The image it runs in has to carry the native client. |

Every example in this README is taken from a source file that these modules compile and run;
`ReadmeExamplesTests` fails if the two drift apart.

## Design documents

- `.docs/design/architecture.md` — the design and the reasoning behind it.
- `.docs/design/redis-command-surface.md` — the exact command and keyspace behaviour.
- `.docs/design/etcd-performance.md` — what the etcd backend costs, and how it was measured.
- `.docs/research/` — how Spring Session uses Redis, cited line by line.

## License

Licensed under the Apache License, Version 2.0.
