package io.github.idavydov.ivybp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GattTimeoutTest {
 @Test fun firstRecordCanArriveAfterIdleTimeout() = runBlocking {
  assertEquals("record",awaitMeasurement(false,firstTimeoutMs=100,idleTimeoutMs=5) { delay(25); "record" })
 }
 @Test fun noPacketsMustNotBeReportedAsSuccessfulEmptySync() = runBlocking {
  try { awaitMeasurement(false,firstTimeoutMs=5) { awaitCancellation() }; fail("Expected no-data failure") }
  catch(e: IOException) { assertTrue(e.message!!.contains("no measurement")) }
 }
 @Test fun silenceAfterARecordEndsTheStreamNormally() = runBlocking {
  assertNull(awaitMeasurement(true,idleTimeoutMs=5) { awaitCancellation() })
 }

 @Test fun operationTimeoutIsRetryableAndIdentifiesStage() = runBlocking {
  try { awaitGatt("service discovery",10) { awaitCancellation() }; fail("Expected timeout") }
  catch(e: IOException) { assertTrue(e.message!!.contains("service discovery")) }
 }
 @Test fun parentCancellationStillPropagates() = runBlocking {
  try {
   withTimeout(20) { awaitGatt("connection",1000) { awaitCancellation() } }
   fail("Expected cancellation")
  } catch(e: TimeoutCancellationException) { /* Outer session deadline remains cancellation. */ }
 }
}
