package com.twitter.finagle.memcached.integration.external

import _root_.java.net.SocketAddress
import com.twitter.finagle.Memcached.Server
import com.twitter.finagle.{ListeningServer, Memcached}
import com.twitter.finagle.memcached.util.AtomicMap
import com.twitter.finagle.memcached.{Entry, Interpreter, InterpreterService}
import com.twitter.io.Buf
import com.twitter.util.Await
import java.util.concurrent.ConcurrentMap
import java.util.{Collections, LinkedHashMap}
import scala.collection.JavaConverters._

class InProcessMemcached(address: SocketAddress) {
  val concurrencyLevel: Int = 16
  val slots: Int = 500000
  val slotsPerLru: Int = slots / concurrencyLevel
  val maps: Seq[scala.collection.mutable.Map[Buf, Entry]] = (0 until concurrencyLevel) map { i =>
    Collections
      .synchronizedMap(
        new LinkedHashMap[Buf, Entry](
          16, /* initial capacity */
          0.75f, /* load factor */
          true /* access order (as opposed to insertion order) */
        ) {
          override protected def removeEldestEntry(
            eldest: java.util.Map.Entry[Buf, Entry]
          ): Boolean = {
            this.size() > slotsPerLru
          }
        }).asScala
  }

  private[this] val service: InterpreterService = {
    val interpreter = new Interpreter(new AtomicMap(maps))
    new InterpreterService(interpreter)
  }

  private[this] val serverSpec: Server = Memcached.server.withLabel("finagle")

  private[this] var server: Option[ListeningServer] = None

  def start(): ListeningServer = {
    server = Some(serverSpec.serve(address, service))
    server.get
  }

  def stop(blocking: Boolean = false): Unit = {
    server.foreach { server =>
      if (blocking) Await.result(server.close())
      else server.close()
      this.server = None
    }
  }
}
