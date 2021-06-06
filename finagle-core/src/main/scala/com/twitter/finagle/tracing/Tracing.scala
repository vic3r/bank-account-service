package com.twitter.finagle.tracing

import com.twitter.finagle.Init
import com.twitter.finagle.stats.FinagleStatsReceiver
import com.twitter.util.{Duration, Future, Stopwatch, Time}
import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.util.Random

object Tracing {

  private val Rng = new Random
  private[tracing] val sampled = FinagleStatsReceiver.counter("tracing", "sampled")

  private val DefaultId = TraceId(
    None,
    None,
    SpanId(Rng.nextLong()),
    None,
    Flags(),
    if (traceId128Bit()) Some(nextTraceIdHigh()) else None
  )

  /**
   * Some tracing systems such as Amazon X-Ray encode the original timestamp in
   * order to enable even partitions in the backend. As sampling only occurs on
   * low 64-bits anyway, we encode epoch seconds into high-bits to support
   * downstreams who have a timestamp requirement.
   *
   * The 128-bit trace ID (composed of high/low) composes to the following:
   * |---- 32 bits for epoch seconds --- | ---- 96 bits for random number --- |
   */
  private[tracing] def nextTraceIdHigh(): SpanId = {
    val epochSeconds = Time.now.sinceEpoch.inSeconds
    val random = Rng.nextInt()
    SpanId((epochSeconds & 0xffffffffL) << 32 | (random & 0xffffffffL))
  }

  // A collection of methods to work with tracers stored in the local context.
  // Structured as an implicit syntax for ergonomics.
  private implicit class Tracers(val ts: Seq[Tracer]) extends AnyVal {

    @tailrec
    final def isActivelyTracing(id: TraceId): Boolean =
      if (ts.isEmpty) false
      else ts.head.isActivelyTracing(id) || ts.tail.isActivelyTracing(id)

    @tailrec
    final def record(r: Record): Unit =
      if (ts.nonEmpty) {
        ts.head.record(r)
        ts.tail.record(r)
      }
  }
}

/**
 * This is a tracing system similar to Dapper:
 *
 *   “Dapper, a Large-Scale Distributed Systems Tracing Infrastructure”,
 *   Benjamin H. Sigelman, Luiz André Barroso, Mike Burrows, Pat
 *   Stephenson, Manoj Plakal, Donald Beaver, Saul Jaspan, Chandan
 *   Shanbhag, 2010.
 *
 * It is meant to be independent of whatever underlying RPC mechanism is being used,
 * and it is up to the underlying codec to implement the transport.
 *
 * `Trace` (a singleton object) maintains the state of the tracing stack stored in
 * [[com.twitter.finagle.context.Contexts]]. The current [[TraceId]] has a `terminal` flag,
 * indicating whether it can be overridden with a different [[TraceId]]. Setting the current
 * [[TraceId]] as terminal forces all future annotation to share that [[TraceId]]. When reporting,
 * we report to all tracers in the list of `Tracer`s.
 *
 * The [[Tracing]] API is structured in a way it's caller's responsibility to check
 * if the current stack of tracers is actively tracing (`Trace.isActivelyTracing`)
 * to avoid unnecessarily allocations.
 *
 * It's recommended to "capture" a [[Tracing]] instance while performing multiple tracing
 * operations to minimize the number of [[com.twitter.finagle.context.Contexts]] lookups
 * and increase throughput.
 *
 * {{{
 *   // Performs six context lookups (two for isActivelyTracing, two for each record call).
 *   if (Trace.isActivelyTracing()) {
 *     Trace.record("foo")
 *     Trace.record("foo")
 *   }
 *
 *   // Performs just two context lookups and captures the results in the `Trace` instance.
 *   val trace = Trace()
 *   if (trace.isActivelyTracing) {
 *     trace.record("foo")
 *     trace.record("bar")
 *   }
 * }}}
 *
 * @note Use `Trace.getInstance()` and `Trace.newInstance()` in Java.
 */
abstract class Tracing {

  import Tracing._

  /**
   * @return the current list of tracers
   */
  def tracers: Seq[Tracer]

  /**
   * Get the current identifier, if it exists.
   */
  def idOption: Option[TraceId]

  /**
   * True if there is an identifier for the current trace.
   */
  def hasId: Boolean = idOption.nonEmpty

  /**
   * Create a derived id from the current [[TraceId]].
   */
  final def nextId: TraceId = {
    var nextLong = 0L
    while (nextLong == 0L) {
      // NOTE: spanId of 0 is invalid, so guard against that here.
      nextLong = Rng.nextLong()
    }

    val spanId = SpanId(nextLong)

    idOption match {
      case Some(id) =>
        TraceId(Some(id.traceId), Some(id.spanId), spanId, id.sampled, id.flags, id.traceIdHigh)
      case None =>
        val traceIdHigh = if (traceId128Bit()) Some(nextTraceIdHigh()) else None
        TraceId(None, None, spanId, None, Flags(), traceIdHigh)
    }
  }

  /**
   * Record a raw [[Record]]. This will record to a _unique_ set of tracers in the stack.
   */
  final def record(rec: Record): Unit = tracers.record(rec)

  /**
   * Get the current trace identifier. If no identifiers have been pushed,
   * a default one is provided.
   */
  final def id: TraceId = idOption match {
    case Some(tid) => tid
    case _ => DefaultId
  }

  /**
   * Return true if tracing is enabled with a good tracer pushed and at least one tracer
   * decides to actively trace the current [[id]].
   */
  final def isActivelyTracing: Boolean =
    Trace.enabled && {
      val ts = tracers
      ts.nonEmpty && ts.isActivelyTracing(id)
    }

  /**
   * Return true if the current trace [[id]] is terminal.
   */
  final def isTerminal: Boolean = id.terminal

  // The underlying tracing format allows us to add annotations to
  // traces with microsecond resolution. Unfortunately Time.now only
  // gives us millisecond resolution so we need to use a higher
  // precision clock for our timestamps. We use a nanosecond clock,
  // which will allow us to truncate to microseconds when
  // we finally persist this trace.
  final def record(ann: Annotation): Unit =
    record(Record(id, Time.nowNanoPrecision, ann, None))

  final def record(ann: Annotation, duration: Duration): Unit =
    record(Record(id, Time.nowNanoPrecision, ann, Some(duration)))

  final def recordWireSend(): Unit =
    record(Annotation.WireSend)

  final def recordWireRecv(): Unit =
    record(Annotation.WireRecv)

  final def recordWireRecvError(error: String): Unit =
    record(Annotation.WireRecvError(error))

  final def recordClientSend(): Unit =
    record(Annotation.ClientSend)

  final def recordClientRecv(): Unit =
    record(Annotation.ClientRecv)

  final def recordClientRecvError(error: String): Unit =
    record(Annotation.ClientRecvError(error))

  final def recordServerSend(): Unit =
    record(Annotation.ServerSend)

  final def recordServerRecv(): Unit =
    record(Annotation.ServerRecv)

  final def recordServerSendError(error: String): Unit =
    record(Annotation.ServerSendError(error))

  final def recordClientSendFragment(): Unit =
    record(Annotation.ClientSendFragment)

  final def recordClientRecvFragment(): Unit =
    record(Annotation.ClientRecvFragment)

  final def recordServerSendFragment(): Unit =
    record(Annotation.ServerSendFragment)

  final def recordServerRecvFragment(): Unit =
    record(Annotation.ServerRecvFragment)

  final def record(message: String): Unit =
    record(Annotation.Message(message))

  // NOTE: This API is broken and silently discards the duration
  @deprecated("Use Trace#traceLocal instead", "2019-20-10")
  final def record(message: String, duration: Duration): Unit =
    record(Annotation.Message(message), duration)

  final def recordServiceName(serviceName: String): Unit =
    record(Annotation.ServiceName(serviceName))

  final def recordRpc(name: String): Unit =
    record(Annotation.Rpc(name))

  final def recordClientAddr(ia: InetSocketAddress): Unit =
    record(Annotation.ClientAddr(ia))

  final def recordServerAddr(ia: InetSocketAddress): Unit =
    record(Annotation.ServerAddr(ia))

  final def recordLocalAddr(ia: InetSocketAddress): Unit =
    record(Annotation.LocalAddr(ia))

  final def recordBinary(key: String, value: Any): Unit =
    record(Annotation.BinaryAnnotation(key, value))

  final def recordBinaries(annotations: Map[String, Any]): Unit = {
    for ((key, value) <- annotations) {
      recordBinary(key, value)
    }
  }

  private[this] def serviceName: String = {
    TraceServiceName() match {
      case Some(name) => name
      case None => "local"
    }
  }

  val LocalBeginAnnotation: String = "local/begin"
  val LocalEndAnnotation: String = "local/end"

  /**
   * Convenience method for event loops in services.  Put your
   * service handling code inside this to get proper tracing with all
   * the correct fields filled in.
   */
  def traceService[T](
    service: String,
    rpc: String,
    hostOpt: Option[InetSocketAddress] = None
  )(
    f: => T
  ): T = Trace.letId(nextId) {
    if (isActivelyTracing) {
      recordBinary("finagle.version", Init.finagleVersion)
      recordServiceName(service)
      recordRpc(rpc)

      hostOpt match {
        case Some(addr) => recordServerAddr(addr)
        case None =>
      }

      record(Annotation.ServerRecv)
      try f
      finally record(Annotation.ServerSend)
    } else f
  }

  /**
   * Create a span that begins right before the function is called
   * and ends immediately after the function completes. This
   * span will never have a corresponding remote component and is contained
   * completely within the process it is created.
   */
  def traceLocal[T](name: String)(f: => T): T = {
    Trace.letId(nextId) {
      if (isActivelyTracing) {
        val timestamp = Time.nowNanoPrecision
        try f
        finally {
          val duration = Time.nowNanoPrecision - timestamp
          recordSpan(name, timestamp, duration)
        }
      } else f
    }
  }

  /**
   * Create a span that begins right before the function is called
   * and ends immediately after the async operation completes. This span will
   * never have a corresponding remote component and is contained
   * completely within the process it is created.
   */
  def traceLocalFuture[T](name: String)(f: => Future[T]): Future[T] = {
    Trace.letId(nextId) {
      if (isActivelyTracing) {
        val timestamp = Time.nowNanoPrecision
        f.ensure {
          val duration = Time.nowNanoPrecision - timestamp
          recordSpan(name, timestamp, duration)
        }
      } else f
    }
  }

  /**
   * Create a span with the given name and Duration, with the end of the span at `Time.now`.
   */
  def traceLocalSpan(name: String, duration: Duration): Unit = {
    Trace.letId(nextId) {
      if (isActivelyTracing) {
        recordSpan(name, Time.nowNanoPrecision - duration, duration)
      }
    }
  }

  /**
   * Create a span with the given name, timestamp and Duration. This is useful for debugging, or
   * if you do not have complete control over the whole execution, e.g. you can not use
   * [[traceLocalFuture]].
   */
  def traceLocalSpan(name: String, timestamp: Time, duration: Duration): Unit = {
    Trace.letId(nextId) {
      if (isActivelyTracing) {
        recordSpan(name, timestamp, duration)
      }
    }
  }

  private[this] def recordSpan(name: String, timestamp: Time, duration: Duration): Unit = {
    if (isActivelyTracing) {
      // these annotations are necessary to get the
      // zipkin ui to properly display the span.
      record(Record(id, timestamp, Annotation.Rpc(name)))
      record(Record(id, timestamp, Annotation.ServiceName(serviceName)))
      record(Record(id, timestamp, Annotation.BinaryAnnotation("lc", name)))
      record(Record(id, timestamp, Annotation.Message(LocalBeginAnnotation)))
      record(Record(id, timestamp + duration, Annotation.Message(LocalEndAnnotation)))
    }
  }

  /**
   * Time an operation and add a binary annotation to the current span
   * with the duration.
   *
   * @param message The message describing the operation
   * @param f operation to perform
   * @tparam T return type
   * @return return value of the operation
   */
  def time[T](message: String)(f: => T): T = {
    if (isActivelyTracing) {
      val elapsed = Stopwatch.start()
      val rv = f
      recordBinary(message, elapsed())
      rv
    } else f
  }

  /**
   * Time an async operation and add a binary annotation to the current span
   * with the duration.
   */
  def timeFuture[T](message: String)(f: Future[T]): Future[T] = {
    if (isActivelyTracing) {
      val elapsed = Stopwatch.start()
      f.ensure(recordBinary(message, elapsed()))
    } else f
  }
}
