# Periodic
[![Maven](https://img.shields.io/maven-central/v/ca.dvgi/periodic-core_2.13?color=blue)](https://search.maven.org/search?q=g:ca.dvgi%20periodic) [![CI](https://img.shields.io/github/actions/workflow/status/dvgica/periodic/ci.yml?branch=main)](https://github.com/dvgica/periodic/actions)

Periodic is a low-dependency Scala library providing:

- an in-memory cached variable (`AutoUpdatingVar`) that self-updates on a periodic basis
- a periodic runner for a side-effecting function (`FnRunner`)

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

### `FnRunner`
`FnRunner` is useful when you want to do something periodically, but don't need to make any data available. Concrete use cases include:

- deleting old records in a database
- triggering calls to an external service

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

`JdkPeriodic` is the default implementation provided in `periodic-core`, which is is suitable for many use cases. Usages of the `jdk` and `jdkFuture` methods on the `AutoUpdatingVar` and `FnRunner` companion objects create a new, non-shared `JdkPeriodic` (and thus a new thread) for each invocation. This will work well as long as the number of threads is not problematic for your application.

Users with many `AutoUpdatingVar`s or `FnRunner`s may wish to share a `JdkPeriodic` between them to decrease the total number of threads used. In this case, the shared `JdkPeriodic` may need to be tuned based on workload. Specifically, users may need to provide a `ScheduledExecutorService` to the shared `JdkPeriodic` with an increased thread count (the default number of threads used by a `JdkPeriodic` is one). Threads in the `ScheduledExecutorService` will be blocked.

The JDK implementation works out of the box with sync (`Identity`) or async (`scala.concurrent.Future`) update code. If usage with another effect is desired, provide a typeclass implementation of `ca.dvgi.periodic.jdk.Eval`.

#### Pekko Streams Implementation

The Pekko Streams implementation is completely non-blocking and does not need additional resources besides an `ActorSystem`. A single `PekkoStreamsPeriodic` can be shared by many `AutoUpdatingVar`s and `FnRunner`s without requiring tuning. It is recommended if you are already using Pekko or don't mind the extra dependency.

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
  PekkoStreamsPeriodic() // can also be shared by many AutoUpdatingVars or FnRunners
)(
  updateData(),
  UpdateInterval.Static(1.second),
  AttemptStrategy.Infinite(5.seconds)
)

```

### `FnRunner`

``` scala
import ca.dvgi.periodic._
import scala.concurrent.duration._
import java.time.Instant

def doSomething(): FiniteDuration = {
  println(s"the time is: ${Instant.now.toString}")
  10.seconds
}

// alternately use FnRunner.jdkFuture or FnRunner.apply(somePeriodic)
val runner = FnRunner.jdk(doSomething, AttemptStrategy.Infinite(1.second), "time printer")
```
## Contributing 

Contributions in the form of Issues and PRs are welcome.
