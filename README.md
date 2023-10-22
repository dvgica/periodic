# Periodic
[![Maven](https://img.shields.io/maven-central/v/ca.dvgi/periodic-core_2.13?color=blue)](https://search.maven.org/search?q=g:ca.dvgi%20periodic) [![CI](https://img.shields.io/github/actions/workflow/status/dvgica/periodic/ci.yml?branch=main)](https://github.com/dvgica/periodic/actions)

Periodic is a low-dependency Scala library providing an in-memory cached variable (`AutoUpdatingVar`) that self-updates on a periodic basis.

- [Motivation](#motivation)
- [Installation](#installation)
- [Usage Example](#usage-example)
- [Contributing](#contributing)

## Motivation

This library is useful for caching semi-static data in memory and having that data be automatically and periodically updated. The source of the data is typically another process that can be queried to get a new data value. If the cached data becomes stale at a predictable interval, the cached data can be updated before this occurs. If the cached data becomes stale at unpredictable times, the stale data must still be usable. Concrete use cases include:

- caching a time-limited key or token, and replacing it with a new one before it expires (e.g. an OAuth access token)
- caching data that changes irregularly and occasionally, such as a list of a country's airports and their codes

For data that changes irregularly but must be up-to-date, you likely want to be subscribing to some kind of change event instead.

## Installation

Periodic is available on Maven Central for Scala 2.12, 2.13, and 3. Java 11+ is required.

For the default JDK-based implementation, add the following dependency:

`"ca.dvgi" %% "periodic-core" % "<latest>"`

### Dependencies
- `periodic-core` depends only on `slf4j-api`

## Usage Example

Using the default JDK implementation:

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
  UpdateAttemptStrategy.Infinite(5.seconds), 
)

// `ready` returns a `Future[Unit]` which completes when the initial data initialization is complete
// see also the `blockUntilReadyTimeout` parameter
Await.result(data.ready, 5.seconds)

// the data is cached in-memory and can be access using `latest`
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

### Alternate Implementations

Alternate implementations are used by passing an `AutoUpdater` to `AutoUpdatingVar.apply`:

``` scala
AutoUpdatingVar(
  SomeOtherAutoUpdater[String]() // T must be explicitly provided, it can't be inferred
)(
  updateData(),
  UpdateInterval.Static(1.second),
  UpdateAttemptStrategy.Infinite(5.seconds)
)
```

## Contributing 

Contributions in the form of Issues and PRs are welcome.
