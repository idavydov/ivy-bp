package io.github.idavydov.ivybp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

data class Measurement(val systolic: Int, val diastolic: Int, val pulse: Int, val deviceSeconds: Long)
interface ProtocolTransport {
 suspend fun subscribe(uuid: UUID)
 suspend fun challenge(): ByteArray
 suspend fun write(bytes: ByteArray)
}
object Protocol {
 fun normalizeAddress(value: String): String {
  val address=value.trim().uppercase(java.util.Locale.ROOT)
  require(address.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))) { "Invalid Bluetooth address" }
  return address
 }
 fun savedAddress(value: String): String = if(value.isBlank()) "" else normalizeAddress(value)

 suspend fun handshake(io: ProtocolTransport, clockBytes: ByteArray = clock()) {
  io.subscribe(reply)
  io.write(response(io.challenge()))
  io.write(clockBytes)
  io.subscribe(data)
 }
 fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
 fun matchesAdvertisement(name: String?, services: List<UUID>): Boolean = name?.startsWith("B810A0")==true || service in services
 val service = uuid("7809"); val command = uuid("8a81"); val reply = uuid("8a82"); val data = uuid("8a91"); val cccd = uuid("2902")
 fun response(challenge: ByteArray): ByteArray {
  require(challenge.size == 5 && challenge[0] == 0xa1.toByte()) { "Invalid authentication challenge" }
  val key = byteArrayOf(0xb7.toByte(), 0x28, 0xc5.toByte(), 0x64)
  return byteArrayOf(0x20) + ByteArray(4) { (challenge[it + 1].toInt() xor key[it].toInt()).toByte() }
 }
 fun clock(now: Calendar = Calendar.getInstance()): ByteArray {
  val shifted = (now.clone() as Calendar).apply { add(Calendar.YEAR, -40) }
  val zone = now.timeZone
  val dst = if (zone.inDaylightTime(now.time)) zone.dstSavings else 0
  val seconds = shifted.timeInMillis / 1000 + zone.getOffset(15L) / 1000 + dst / 1000
  return byteArrayOf(2) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seconds.toInt()).array()
 }
 fun decode(bytes: ByteArray): Measurement {
  require(bytes.size >= 13 && bytes[0] == 0x1e.toByte()) { "Unknown measurement format" }
  val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  return Measurement(b.getShort(1).toInt() and 65535, b.getShort(3).toInt() and 65535,
   b.getShort(11).toInt() and 65535, b.getInt(7).toLong() and 0xffffffffL)
 }
 fun deviceTime(seconds: Long): String = LocalDateTime.of(2010,1,1,0,0).plusSeconds(seconds).toString()
 fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
 fun unhex(value: String): ByteArray {
  require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]*"))) { "Invalid packet hex" }
  return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 }
}
