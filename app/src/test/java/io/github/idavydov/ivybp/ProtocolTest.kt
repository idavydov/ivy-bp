package io.github.idavydov.ivybp
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.nio.ByteBuffer
import java.nio.ByteOrder
class ProtocolTest {
 @Test fun companionAddressIsCanonicalBeforeConnection() {
  assertEquals("AA:BB:CC:DD:EE:FF",Protocol.normalizeAddress("aa:bb:cc:dd:ee:ff"))
  assertEquals("AA:BB:CC:DD:EE:FF",Protocol.savedAddress("  aA:Bb:cC:Dd:eE:Ff  "))
  assertEquals("",Protocol.savedAddress(""))
 }
 @Test(expected=IllegalArgumentException::class) fun rejectInvalidAddress() { Protocol.normalizeAddress("not an address") }

 @Test fun directDiscoveryMatchesAdvertisedNameOrService() {
  assertTrue(Protocol.matchesAdvertisement("B810A001020304",emptyList()))
  assertTrue(Protocol.matchesAdvertisement(null,listOf(Protocol.service)))
  assertFalse(Protocol.matchesAdvertisement("Other device",emptyList()))
 }
 @Test fun handshakeOrderAndFailure() = kotlinx.coroutines.runBlocking {
  suspend fun execute(reject: Boolean): List<String> {
   val calls=mutableListOf<String>()
   val io=object: ProtocolTransport {
    override suspend fun subscribe(uuid: java.util.UUID) { calls.add(uuid.toString()) }
    override suspend fun challenge() = Protocol.unhex("a190001b01")
    override suspend fun write(bytes: ByteArray) { calls.add(Protocol.hex(bytes)); if(reject && bytes[0]==2.toByte()) error("rejected") }
   }
   try { Protocol.handshake(io,Protocol.unhex("0200000000")); check(!reject) } catch(e: IllegalStateException) { check(reject) }
   return calls
  }
  assertEquals(listOf(Protocol.reply.toString(),"202728de65","0200000000",Protocol.data.toString()),execute(false))
  assertFalse(execute(true).contains(Protocol.data.toString()))
 }
 @Test fun authenticationUsesApkKey() { assertEquals("202728de65",Protocol.hex(Protocol.response(Protocol.unhex("a190001b01")))) }
 @Test fun readsSixteenByteSyntheticRecord() {
  val b=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
  b.put(0x1e).putShort(123).putShort(78).putShort(0).putInt(86400).putShort(65).put(byteArrayOf(0,0,0))
  val r=Protocol.decode(b.array())
  assertEquals(Measurement(123,78,65,86400),r)
  assertEquals("2010-01-02T00:00",Protocol.deviceTime(r.deviceSeconds))
 }
 @Test(expected=IllegalArgumentException::class) fun rejectsWrongHeader() { Protocol.decode(ByteArray(16)) }
 @Test(expected=IllegalArgumentException::class) fun rejectsTruncatedRecord() { Protocol.decode(byteArrayOf(0x1e)) }
 @Test fun clockUsesCalendarAndLittleEndian() {
  val c=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear();set(2026,Calendar.OCTOBER,29,9,0,0) }
  val shifted=(c.clone() as Calendar).apply { add(Calendar.YEAR,-40) }
  val bytes=Protocol.clock(c)
  assertEquals(2,bytes[0].toInt())
  assertEquals((shifted.timeInMillis/1000).toInt(),ByteBuffer.wrap(bytes,1,4).order(ByteOrder.LITTLE_ENDIAN).int)
 }
}
