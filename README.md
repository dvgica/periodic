# Periodic
[![Maven](https://img.shields.io/maven-central/v/ca.dvgi/periodic_2.13?color=blue)](https://search.maven.org/search?q=g:ca.dvgi%20periodic) [![CI](https://img.shields.io/github/actions/workflow/status/dvgica/periodic/ci.yml?branch=main)](https://github.com/dvgica/periodic/actions)

Periodic is a Scala library providing a variable that self-updates on a periodic basis.

- [Motivation](#motivation)
- [Installation](#installation)
- [Usage](#usage)
- [Contributing](#contributing)

## Motivation

This library is useful for caching semi-static data in memory and having that data automatically updated periodically. The source of the data is typically another process that can be queried to get a new data value. If the cached data becomes stale at a predictable interval, the cached data can be updated before this occurs. If the cached data becomes stale at unpredictable times, the stale data must still be usable. Concrete use cases include:

- caching a time-limited key or token, and replacing it with a new one before it expires (e.g. an OAuth access token)
- caching data that changes irregularly and occasionally, such as a list of a country's airports and their codes

## Installation

Periodic is available on Maven Central for Scala 2.12, 2.13, and 3. Java 11+ is required.

There is currently a `periodic-api` project which exposes an interface for `AutoUpdatingVar`, and a `periodic-jdk` project which implements the API using primitives included in the JDK. Other implementations may follow.

If a specific implementation is required, add the following dependency to your project:

`"ca.dvgi" %% "periodic-jdk" % "<latest>"`

If only the interface is required, add:

`"ca.dvgi" %% "periodic-api" % "<latest>"`

## Usage

``` scala
import ca.dvgi.periodic.jdk._
import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.concurrent.Await
import java.time.Instant

def updateData(): String = Instant.now.toString

val data = new JdkAutoUpdatingVar(
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
// data has not been updated yet, same result
println(s"Cached data is still ${data.latest}")

Thread.sleep(2000)

// the `AutoUpdatingVar` fetched new data while this thread was sleeping
println(s"New cached data is ${data.latest}")
```

This returns something like:
```
Cached data is 2023-10-19T02:35:22.467418Z
Cached data is still 2023-10-19T02:35:22.467418Z
New cached data is 2023-10-19T02:35:23.474155Z
```

For handling errors during update, and other options, see the Scaladocs. 

## Contributing 

Contributions in the form of Issues and PRs are welcome.
