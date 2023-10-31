# Periodic
[![Maven](https://img.shields.io/maven-central/v/ca.dvgi/periodic-core_2.13?color=blue)](https://search.maven.org/search?q=g:ca.dvgi%20periodic) [![CI](https://img.shields.io/github/actions/workflow/status/dvgica/periodic/ci.yml?branch=main)](https://github.com/dvgica/periodic/actions)

Periodic is a low-dependency Scala library providing:

- an in-memory cached variable (`AutoUpdatingVar`) that self-updates on a periodic basis

It attempts to provide an effect- and runtime-agnostic API which can abstract various implementations as needed.

- [Motivation](#motivation)
- [Installation](#installation)
- [Usage](#usage-example)
- [Contributing](#contributing)

## Motivation

It is fairly common to need to do something periodically, while a process is running.

### `AutoUpdatingVar`
`AutoUpdatingVar` is useful for caching semi-static data in memory and having that data be automatically and periodically updated. The source of the data is typically another process that can be queried to get a new data value. If the cached data becomes stale at a predictable interval, the cached data can be updated before this occurs. If the cached data becomes stale at unpredictable times, the stale data must still be usable. Concrete use cases include:

- caching a time-limited key or token, and replacing it with a new one before it expires (e.g. an OAuth access token)
- caching data that changes irregularly and occasionally, such as a list of a country's airports and their codes

For data that changes irregularly but must be up-to-date, you likely want to be subscribing to some kind of change event instead.

## Installation

Periodic is available on Maven Central for Scala 2.12, 2.13, and 3. Java 11+ is required.

For the **default JDK-based implementation**, add the following dependency:

```
"ca.dvgi" %% "periodic-core" % "<latest>"
```

For the **Pekko Streams-based implementation**, use this dependency:

```
"ca.dvgi" %% "periodic-pekko-stream" % "<latest>"
```

### Dependencies
- `periodic-core` depends only on `slf4j-api`
- `periodic-pekko-stream` depends on `pekko-stream` and `periodic-core`

## Usage

### Periodic

All library functionality is based on implementations of `Periodic`. Therefore all classes require an instance of `Periodic` in their constructor. 

#### JDK Implementation

`JdkPeriodic` is the default implementation provided in `periodic-core`. It is suitable for most usages, although users with many `AutoUpdatingVar`s or `Runner`s may wish to provide a shared `ScheduledExecutorService` to them, to avoid starting many threads. The number of threads in this shared `ScheduledExecutorService` will need to be tuned based on workload. Threads in the `ScheduledExecutorService` will be blocked.

The JDK implementation works out of the box with sync (`Identity`) or async (`scala.concurrent.Future`) update code. If usage with another effect is desired, provide a typeclass implementation of `ca.dvgi.periodic.jdk.Eval`.

#### Pekko Streams Implementation

The Pekko Streams implementation is completely non-blocking, does not need additional resources besides an `ActorSystem`, and will scale to many `AutoUpdatingVar`s and `Runner`s without requiring tuning. It is recommended if you are already using Pekko or don't mind the extra dependency.

The Pekko Streams implementation only works with `scala.concurrent.Future`.

### `AutoUpdatingVar`

#### Default JDK-based Implementation

``` scala
import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.concurrent.Await
import java.time.Instant

def updateData(): String = Instant.now.toString

val data = AutoUpdatingVar.jdk( // or AutoUpdatingVar.jdkFuture if updateData returns a Future
  updateData(),
  // can also be dynamic based on the last data
  UpdateInterval.Static(1.second), 
  // can also be finite with configurable behavior for attempt exhaustion
  AttemptStrategy.Infinite(5.seconds), 
)

// `ready` returns a `Future[Unit]` which completes when the initial data initialization is complete
// see also the `blockUntilReadyTimeout` parameter
Await.result(data.ready, 5.seconds)

// the data is cached in memory and can be accessed using `latest`
println(s"Cached data is ${data.latest}")
Thread.sleep(10)
// data has not been updated yet, same result
println(s"Cached data is still ${data.latest}")

Thread.sleep(1100)

// the `AutoUpdatingVar` fetched new data while this thread was sleeping
println(s"New cached data is ${data.latest}")
```

This results in the following output:
```
Cached data is 2023-10-19T02:35:22.467418Z
Cached data is still 2023-10-19T02:35:22.467418Z
New cached data is 2023-10-19T02:35:23.474155Z
```

For handling errors during update, and other options, see the Scaladocs.

#### Pekko Streams Implementation

``` scala
import org.apache.pekko.actor.ActorSystem
import ca.dvgi.periodic.pekko.stream.PekkoStreamsPeriodic
import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.concurrent.Future
import java.time.Instant

def updateData(): Future[String] = Future.successful(Instant.now.toString)

implicit val actorSystem = ActorSystem() // generally you should have an ActorSystem in your process already

val data = AutoUpdatingVar(
  PekkoStreamsPeriodic[String]() // T must be explicitly provided, it can't be inferred
)(
  updateData(),
  UpdateInterval.Static(1.second),
  AttemptStrategy.Infinite(5.seconds)
)
```

## Contributing 

Contributions in the form of Issues and PRs are welcome.
