package com.twitter.finagle

import com.twitter.util.{Future, Var, Witness}
import java.net.{InetSocketAddress, SocketAddress}
import org.scalatest.funsuite.AnyFunSuite

class NameTest extends AnyFunSuite {
  test("Name.fromGroup") {
    val g = Group.mutable[SocketAddress]()
    val n = Name.fromGroup(g)

    var addr: Addr = Addr.Pending
    n.addr.changes.register(Witness({ addr = _: Addr }))
    assert(addr == Addr.Pending)
    val set = Set[SocketAddress](new InetSocketAddress(0), new InetSocketAddress(1))
    g() = set

    val Addr.Bound(s2, r) = addr
    assert(s2.collect { case Address.Inet(ia, _) => ia } == set)
    assert(r.isEmpty)

    g() = Set(new SocketAddress {})
    val Addr.Failed(e) = addr
    assert(e.isInstanceOf[IllegalArgumentException])
  }

  test("Name.Bound maintains equality as per 'id'") {
    val id1, id2 = new {}
    val a1, a2 = Var(Addr.Pending)

    assert(Name.Bound(a1, id1) == Name.Bound(a2, id1))
    assert(Name.Bound(a1, id1) != Name.Bound(a1, id2))

    // It sucks that this is not symmetric, oh well.
    assert(Name.Bound(a1, id1) == id1)
    assert(Name.Bound(a1, id1) != id2)
  }

  test("Name.all maintains equality") {
    val names = Seq.fill(10) { Name.Bound.singleton(Var(Addr.Pending)) }.toSet

    assert(Name.all(names) == Name.all(names))
    assert(Name.all(names) != Name.all(names drop 1))
  }

  test("Name.bound can take a Service for testing purposes") {
    val service: Service[String, String] = Service.mk(_ => Future.value("Hello"))
    val name = Name.bound(service)

    // Sample the Var[Addr]
    val addr = name.addr.sample
    // It should be an Addr.Bound
    addr match {
      case Addr.Bound(addrs, metadata) =>
        // Metadata should be empty
        assert(metadata == Addr.Metadata.empty)
        // Addrs should be a set with a single item being
        // the ServiceFactory for the provided Service
        assert(addrs.size == 1)
        addrs.head match {
          case Address.ServiceFactory(_, _) =>
            // This is good enough as we can't test the equality of the contained
            // 'factory' with only the 'service'.
            succeed
          case _ =>
            fail(s"Expected $addrs to be an Address.ServiceFactory")
        }
      case _ => fail(s"Expected $addr to be an Addr.Bound")
    }
  }
}
