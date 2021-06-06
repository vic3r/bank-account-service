package com.twitter.finagle.stats

import java.util.{Map => JMap}
import scala.collection.JavaConverters._

/**
 * Provides snapshots of metrics values.
 */
private[stats] trait MetricsView {

  /**
   * A read-only snapshot of instantaneous values for all gauges.
   */
  def gauges: JMap[String, Number]

  /**
   * A read-only snapshot of instantaneous values for all counters.
   */
  def counters: JMap[String, Number]

  /**
   * A read-only snapshot of instantaneous values for all histograms.
   */
  def histograms: JMap[String, Snapshot]

  /**
   * A read-only snapshot of verbosity levels attached to each metric.
   * For the sake of efficiency, metrics with verbosity [[Verbosity.Default]]
   * aren't included into a returned map.
   */
  def verbosity: JMap[String, Verbosity]
}

private[stats] object MetricsView {

  private def merge[T](map1: JMap[String, T], map2: JMap[String, T]): JMap[String, T] = {
    val merged = new java.util.HashMap[String, T](map1)
    map2.asScala.foreach {
      case (k, v) =>
        merged.putIfAbsent(k, v)
    }
    java.util.Collections.unmodifiableMap(merged)
  }

  /**
   * Creates a combined view over the inputs. Note that order matters here
   * and should any key be duplicated, the value from the first view (`view1`)
   * is used.
   */
  def of(view1: MetricsView, view2: MetricsView): MetricsView = new MetricsView {
    def gauges: JMap[String, Number] =
      merge(view1.gauges, view2.gauges)

    def counters: JMap[String, Number] =
      merge(view1.counters, view2.counters)

    def histograms: JMap[String, Snapshot] =
      merge(view1.histograms, view2.histograms)

    def verbosity: JMap[String, Verbosity] =
      merge(view1.verbosity, view2.verbosity)
  }

}
