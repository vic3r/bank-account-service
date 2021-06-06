package com.twitter.finagle.mux.transport

import io.netty.channel.ChannelHandler
import io.netty.handler.codec.compression.{Lz4FrameEncoder, Lz4FrameDecoder}

object Compression {
  private[this] val isValidCharacter: Char => Boolean = { c: Char =>
    ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9')
  }

  val Lz4String = "lz4"

  /**
   * Used for configuring the local preferences for compression and
   * decompression.
   */
  case class LocalPreferences(compression: LocalSetting, decompression: LocalSetting) {
    def isDisabled: Boolean =
      compression.level == CompressionLevel.Off &&
        decompression.level == CompressionLevel.Off
  }

  /**
   * Used for communicating the preferences of the client for compression and
   * decompression over the wire.
   */
  case class PeerPreferences(compression: PeerSetting, decompression: PeerSetting)

  /**
   * The setting used for configuring the local preferences for either compression or
   * decompression.
   *
   * @param level how much the party desires to compress
   * @param transformers the ways the client can compress or decompress things
   */
  case class LocalSetting(level: CompressionLevel, transformers: Seq[ByteTransformer])

  /**
   * The setting used for transmitting over the wire the preferences of the
   * client for either compression or decompression.
   *
   * @param level how much the party desires to compress
   * @param transformerNames the names of the ways the client can compress or
   *        decompress things
   */
  case class PeerSetting(level: CompressionLevel, transformerNames: Seq[String])

  /**
   * A named factory that makes a byte transformer that can be installed in the
   * Netty pipeline.
   *
   * This must be called every time you make a new transformer for a differen stream.
   * Transformers cannot share state between dfiferent streams.
   */
  sealed abstract class ByteTransformer(val name: String) {
    protected[mux] def apply(): ChannelHandler

    require(
      name.forall(isValidCharacter),
      s"Transformer $name contained an illegal character (alphanumeric ascii characters are valid)")
  }

  def lz4Compressor(highCompression: Boolean): ByteTransformer = new ByteTransformer(Lz4String) {
    override protected[mux] def apply(): ChannelHandler =
      new Lz4FrameEncoder( /* highCompression */ highCompression)
  }

  def lz4Decompressor(): ByteTransformer = new ByteTransformer(Lz4String) {
    override protected[mux] def apply(): ChannelHandler = new Lz4FrameDecoder()
  }

  private[mux] def createTestByteTransformer(name: String): ByteTransformer =
    new ByteTransformer(name) {
      override protected[mux] def apply(): ChannelHandler = ???
    }

  /**
   * The default preferences if compression format is not specified.
   *
   * Is unable to use compression for either the request or the response.
   * May change in the future.
   */
  val DefaultLocal: LocalPreferences = LocalPreferences(
    LocalSetting(CompressionLevel.Off, Nil),
    LocalSetting(CompressionLevel.Off, Nil)
  )

  /**
   * Is unable to use compression for either the request or the response.
   */
  val PeerCompressionOff: PeerPreferences =
    PeerPreferences(PeerSetting(CompressionLevel.Off, Nil), PeerSetting(CompressionLevel.Off, Nil))
}
