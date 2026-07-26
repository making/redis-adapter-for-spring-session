# session-example-foundationdb

A Spring Boot web application whose HTTP sessions are kept in FoundationDB, through the
[Redis adapter](../../README.md).

The application is what start.spring.io produces for `web`, `thymeleaf` and
`session-data-redis`, plus one screen. Nothing in `src/main` mentions the adapter or
FoundationDB: it has `spring-boot-starter-session-data-redis` on its class path and a host
and a port in `application.properties`, and what is listening on that port is the adapter
rather than Redis.

```text
  browser
     |
     |  SESSION cookie
     v
+--------------+   +--------------+
| app-1  :p1   |   | app-2  :p2   |
+------+-------+   +-------+------+
       |                   |          RESP over TCP
       v                   v
+--------------+   +--------------+
|  adapter 1   |   |  adapter 2   |   the runnable jar, on a JRE image
+------+-------+   +-------+------+   that carries libfdb_c
       |                   |
       +---------+---------+
                 v
          +---------------+
          |  FoundationDB |
          +---------------+
```

## The one thing this backend asks for

FoundationDB serves no HTTP API, so the only way to reach it is its native client,
`libfdb_c` — and that library is in no jar. A deployment installs the FoundationDB client
package into the image the adapter runs in, version-matched to the cluster.

That is the whole of the difference between this example and the others here, and it is
confined to the adapter's image. `SessionStoreContainers` builds that image in the two
lines a deployment would write:

```dockerfile
FROM foundationdb/foundationdb:7.3.63 AS client
FROM eclipse-temurin:25-jre
COPY --from=client /usr/lib/libfdb_c.so /usr/lib/libfdb_c.so
```

Taking the library out of the image the cluster itself runs is what keeps the two version
matched by construction; a mixed pair is not tested anywhere in this project.

**The application links against nothing.** It talks to the adapter over TCP exactly as it
would talk to Redis, and its class path holds no FoundationDB at all. Neither does this
machine: nothing has to be installed to run what is below.

## The screen

One page. Type a name, and it is kept in the session as an ordinary object of the
application's own (`Visitor`, a record). The page then shows who you are, how many times
this session has loaded the page, the session id, and which instance served it. Signing
out invalidates the session, which removes it from the cluster.

Nothing here is written against Spring Session's API. `HttpServletRequest.getSession()` is
the whole of it, which is the point: Spring Session replaces what is behind `HttpSession`
and nothing else.

## Running it

Docker is required — the adapter and FoundationDB both run in containers.

```bash
cd ../..
./mvnw install -DskipTests   # publish the adapter jar to the local repository
cd examples/session-example-foundationdb
./mvnw spring-boot:test-run  # starts FoundationDB, an adapter, and the application
```

Then open <http://localhost:8080>, type a name, and reload the page. `docker ps` shows the
two containers it started:

```text
IMAGE                                         PORTS
session-example-foundationdb-adapter:7.3.63   0.0.0.0:35536->6379/tcp   the adapter, running the jar
foundationdb/foundationdb:7.3.63              0.0.0.0:35535->4500/tcp   where the session actually is
```

That the session is in the cluster rather than in the application can be read out of the
cluster itself, with the `fdbcli` its own image ships:

```bash
docker exec <the foundationdb container> \
  fdbcli --exec 'getrangekeys "" \xff 20'
```

```text
`\x02/redis-adapter/\x00\x14\x02d\x00\x01spring:session:sessions:d8cf65ff-...\x00\x01creationTime\x00'
`\x02/redis-adapter/\x00\x14\x02d\x00\x01spring:session:sessions:d8cf65ff-...\x00\x01lastAccessedTime\x00'
`\x02/redis-adapter/\x00\x14\x02d\x00\x01spring:session:sessions:d8cf65ff-...\x00\x01maxInactiveInterval\x00'
`\x02/redis-adapter/\x00\x14\x02d\x00\x01spring:session:sessions:d8cf65ff-...\x00\x01sessionAttr:visitor\x00'
`\x02/redis-adapter/\x00\x14\x02m\x00\x01spring:session:sessions:d8cf65ff-...\x00'
`\x02/redis-adapter/\x00\x14\x02x\x00\x1a\x01\x9f\x9d\xcc/\x10\x01spring:session:sessions:d8cf65ff-...\x00'
```

The id in those keys is the one the page is showing. There is **one key per hash field**
rather than one blob per session, which is why two instances writing different fields of
one session do not conflict; the `x` key is the deadline index the adapter's sweeper reads,
because FoundationDB has no expiry of any kind and the adapter owns all of it.
[The design](../../.docs/design/architecture.md#131-the-layout-one-key-per-field-and-per-member)
is §13 of the architecture.

`spring-boot:test-run` runs `TestSessionExampleFoundationDbApplication`, which adds
`TestcontainersConfiguration` to the application. That class starts FoundationDB, starts
the adapter in front of it, and hands the application the adapter's host and port through
`@ServiceConnection(name = "redis")` — the same annotation a Redis container would be
given, which is the shortest statement of the substitution this project makes.

The container it starts is chosen by a Spring profile, so the application can be run
against a real Redis instead without anything else changing:

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.profiles=redis
```

To run the application against something you started yourself:

```bash
./mvnw spring-boot:run   # expects an adapter (or a Redis) on localhost:6379
```

`ADAPTER_HOST`, `ADAPTER_PORT` and `INSTANCE_NAME` are the environment variables it reads.

## The tests

```bash
./mvnw test                                  # against the adapter over FoundationDB
./mvnw test -Dspring.profiles.active=redis   # the same tests, against a real Redis
```

The end-to-end tests drive a real browser (Playwright, headless Chromium) against **two**
instances of the application, each started with `TestcontainersConfiguration` — the same
class `spring-boot:test-run` adds. Where those instances keep their sessions is all the
profile changes: by default each instance gets an adapter of its own and the two adapters
share a single FoundationDB, and the `redis` profile puts one ordinary Redis behind both
instead.

Both runs are expected to pass identically. Redis is the oracle — it is what Spring Session
was written against — so a test that passes there and fails against the adapter is the
adapter's fault, and the same three assertions are made either way:

- what you typed survives a page load, and the visit count goes up;
- signing out ends the session, and the next one has a different id;
- a session created against `app-1` is served by `app-2`.

The last one is why a shared backend exists. With the adapter, the instances share nothing
but the cluster behind their adapters, so `app-2` can only know about `app-1`'s session
because FoundationDB holds it. The browser is what carries it across: a cookie is not
scoped to a port, so `localhost:p1` and `localhost:p2` are the same site as far as the
`SESSION` cookie is concerned.

The adapter is started the way a server starts it — `java -jar` on the JRE image above,
with `REDIS_ADAPTER_FOUNDATIONDB_CLUSTER_FILE_CONTENTS` saying where the cluster is. That
property rather than `cluster-file` is what a container platform delivering configuration
instead of files has; the adapter writes the file itself. The contents are read out of the
running FoundationDB, because a cluster file names the address a server *advertises* — and
a FoundationDB client checks that the port it reached is the port that was advertised. That
is why the adapters are on the cluster's Docker network and reach it directly rather than
through a published, remapped port.

Maven copies the adapter's runnable jar into `target/adapter/` before the tests run
(`maven-dependency-plugin` in `pom.xml`), so `./mvnw install -DskipTests` in the repository
root has to have run first. Change `redis-adapter.version` in `pom.xml` to run against a
published version instead.

Playwright downloads its browsers on first use, so the first `./mvnw test` takes a few
minutes longer than the ones after it. The adapter's image is built on first use too, and
tagged, so Docker's build cache makes the runs after that one instant.

## What was generated, and what was added

The project came from start.spring.io:

```bash
curl https://start.spring.io/starter.tgz \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=4.1.0 \
  -d javaVersion=25 \
  -d groupId=com.example \
  -d artifactId=session-example-foundationdb \
  -d name=session-example-foundationdb \
  -d packageName=com.example.session \
  -d dependencies=web,thymeleaf,session-data-redis,testcontainers \
  | tar -xzvf -
```

What was added to it: the screen (`Visitor`, `VisitorController`, `index.html`), the
containers (`SessionStoreContainers`), and the end-to-end tests.
`TestcontainersConfiguration` was generated with a Redis container in it; the adapter took
its place, and the Redis it generated is what the `redis` profile brings back.
