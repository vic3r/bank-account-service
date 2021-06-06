package com.twitter.finagle.mysql

import com.twitter.finagle.Stack
import com.twitter.finagle.mysql.param.{Charset, Credentials, Database, FoundRows}
import org.scalatest.funsuite.AnyFunSuite

class HandshakeSettingsTest extends AnyFunSuite {

  private val initial = Capability(
    Capability.Transactions,
    Capability.MultiResults
  )
  private val withFoundRows = initial + Capability.FoundRows

  test("HandshakeSettings adds FoundRows by default") {
    val settings = HandshakeSettings(clientCapabilities = initial)
    assert(settings.calculatedClientCapabilities == withFoundRows)
  }

  test("HandshakeSettings returns initial when found rows is disabled") {
    val settings = HandshakeSettings(clientCapabilities = initial, enableFoundRows = false)
    assert(settings.calculatedClientCapabilities == initial)
  }

  test("HandshakeSettings adds ConnectWithDB if database is defined") {
    val settings = HandshakeSettings(clientCapabilities = initial, database = Some("test"))
    val withDB = withFoundRows + Capability.ConnectWithDB
    assert(settings.calculatedClientCapabilities == withDB)
  }

  test("HandshakeSettings can calculate settings for SSL/TLS") {
    val settings = HandshakeSettings(clientCapabilities = initial, database = Some("test"))
    val withDB = withFoundRows + Capability.ConnectWithDB
    val withSSL = withDB + Capability.SSL
    assert(settings.sslCalculatedClientCapabilities == withSSL)
  }

  test("HandshakeSettings can read values from Stack params") {
    val params = Stack.Params.empty +
      Charset(MysqlCharset.Binary) +
      Credentials(Some("user123"), Some("pass123")) +
      Database(Some("test")) +
      FoundRows(false)
    val settings = HandshakeSettings(params)
    assert(settings.username == Some("user123"))
    assert(settings.password == Some("pass123"))
    assert(settings.database == Some("test"))
    assert(settings.charset == MysqlCharset.Binary)
    assert(!settings.enableFoundRows)
  }

}
