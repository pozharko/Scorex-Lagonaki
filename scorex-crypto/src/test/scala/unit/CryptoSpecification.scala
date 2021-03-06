package unit

import scorex.account.PrivateKeyAccount
import scorex.crypto.{Base58, SigningFunctionsImpl}

import scala.util.Random

class CryptoSpecification extends FunSuite with Matchers {
  test("base58 roundtrip") {
    val b58 = "1AGNa15ZQXAZUgFiqJ2i7Z2DPU2J6hW62i"
    Base58.encode(Base58.decode(b58).get) shouldBe b58
  }

  test("base58 encode and decode") {
    val data = Random.nextString(50).getBytes
    val encoded = Base58.encode(data)
    val restored = Base58.decode(encoded).get
    restored shouldBe data
  }

  test("sign then verify") {
    val acc = new PrivateKeyAccount(Random.nextString(20).getBytes)
    val data = Random.nextString(30).getBytes

    val sig = SigningFunctionsImpl.sign(acc, data)
    val rightKey = acc.publicKey
    assert(SigningFunctionsImpl.verify(sig, data, rightKey))

    val wrongKey = new PrivateKeyAccount(Random.nextString(20).getBytes).publicKey
    assert(!SigningFunctionsImpl.verify(sig, data, wrongKey))

    val wrongData = data ++ Seq(0:Byte)
    assert(!SigningFunctionsImpl.verify(sig, wrongData, rightKey))
  }
}