# session-example-dynamodb

A Spring Boot web application whose HTTP sessions are kept in DynamoDB, through the
[Redis adapter](../../README.md).

The application is what start.spring.io produces for `web`, `thymeleaf` and
`session-data-redis`, plus one screen. Nothing in `src/main` mentions the adapter or
DynamoDB: it has `spring-boot-starter-session-data-redis` on its class path and a host and
a port in `application.properties`, and what is listening on that port is the adapter
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
|  adapter 1   |   |  adapter 2   |   the runnable jar, in a container
+------+-------+   +-------+------+
       |                   |
       +---------+---------+
                 v
          +------------+
          |  DynamoDB  |             locally: the Floci emulator
          +------------+
```

Locally the DynamoDB is the [Floci](https://floci.io) emulator, because AWS publishes no
DynamoDB you can run. Against real AWS nothing here changes except the environment the
adapter is started with: leave the endpoint override unset and give it a region and
credentials (or an instance role), exactly as the `SPRING_CLOUD_AWS_*` variables in
`SessionStoreContainers` do for the emulator.

## The screen

One page. Type a name, and it is kept in the session as an ordinary object of the
application's own (`Visitor`, a record). The page then shows who you are, how many times
this session has loaded the page, the session id, and which instance served it. Signing
out invalidates the session, which removes it from the store.

Nothing here is written against Spring Session's API. `HttpServletRequest.getSession()` is
the whole of it, which is the point: Spring Session replaces what is behind `HttpSession`
and nothing else.

## Running it

Docker is required — the adapter and the emulator both run in containers.

```bash
cd ../..
./mvnw install -DskipTests   # publish the adapter jar to the local repository
cd examples/session-example-dynamodb
./mvnw spring-boot:test-run  # starts the emulator, an adapter, and the application
```

Then open <http://localhost:8080>, type a name, and reload the page. `docker ps` shows the
two containers it started:

```text
IMAGE                    PORTS
eclipse-temurin:25-jre   0.0.0.0:33367->6379/tcp    the adapter, running the jar
floci/floci:1.5.33       0.0.0.0:33366->4566/tcp    where the session actually is
```

That the session is in the table rather than in the application can be read out of the
store itself, on the port `docker ps` gives for it — `aws dynamodb scan` pointed at that
endpoint works, and so does plain curl against the emulator:

```bash
aws dynamodb scan --endpoint-url http://localhost:33366 --region us-east-1 \
  --table-name redis-adapter --projection-expression pk \
  --output text | head
```

```text
k/0/c3ByaW5nOnNlc3Npb246c2Vzc2lvbnM6MjIxZjlhNmEt...
```

Base64-decode the last path segment and it is `spring:session:sessions:<id>` — the id the
page is showing.

`spring-boot:test-run` runs `TestSessionExampleDynamoDbApplication`, which adds
`TestcontainersConfiguration` to the application. That class starts the emulator, starts
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
./mvnw test                                  # against the adapter over DynamoDB (emulated)
./mvnw test -Dspring.profiles.active=redis   # the same tests, against a real Redis
```

The end-to-end tests drive a real browser (Playwright, headless Chromium) against **two**
instances of the application, each started with `TestcontainersConfiguration` — the same
class `spring-boot:test-run` adds. Where those instances keep their sessions is all the
profile changes: by default each instance gets an adapter of its own and the two adapters
share a single emulated DynamoDB, and the `redis` profile puts one ordinary Redis behind
both instead.

Both runs are expected to pass identically. Redis is the oracle — it is what Spring Session
was written against — so a test that passes there and fails against the adapter is the
adapter's fault, and the same three assertions are made either way:

- what you typed survives a page load, and the visit count goes up;
- signing out ends the session, and the next one has a different id;
- a session created against `app-1` is served by `app-2`.

The last one is why a shared backend exists. With the adapter, the instances share nothing
but the table behind their adapters, so `app-2` can only know about `app-1`'s session
because DynamoDB holds it. The browser is what carries it across: a cookie is not scoped to
a port, so `localhost:p1` and `localhost:p2` are the same site as far as the `SESSION`
cookie is concerned.

The adapter is started the way a server starts it — `java -jar` on a plain JRE image, with
`SPRING_CLOUD_AWS_*` environment variables saying where DynamoDB is and how to sign for it.
Maven copies its runnable jar into `target/adapter/` before the tests run
(`maven-dependency-plugin` in `pom.xml`), so `./mvnw install -DskipTests` in the repository
root has to have run first. Change `redis-adapter.version` in `pom.xml` to run against a
published version instead.

Playwright downloads its browsers on first use, so the first `./mvnw test` takes a few
minutes longer than the ones after it.

## What was generated, and what was added

The project came from start.spring.io:

```bash
curl https://start.spring.io/starter.tgz \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=4.1.0 \
  -d javaVersion=25 \
  -d groupId=com.example \
  -d artifactId=session-example-dynamodb \
  -d name=session-example-dynamodb \
  -d packageName=com.example.session \
  -d dependencies=web,thymeleaf,session-data-redis,testcontainers \
  | tar -xzvf -
```

What was added to it: the screen (`Visitor`, `VisitorController`, `index.html`), the
containers (`SessionStoreContainers`), and the end-to-end tests.
`TestcontainersConfiguration` was generated with a Redis container in it; the adapter took
its place, and the Redis it generated is what the `redis` profile brings back.
