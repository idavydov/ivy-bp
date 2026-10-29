package io.github.idavydov.ivybp
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal suspend fun <T: Any> awaitGatt(stage: String, timeoutMs: Long = 12000, operation: suspend () -> T): T =
 withTimeoutOrNull(timeoutMs) { operation() }
  ?: throw IOException("Timed out waiting for $stage")

internal suspend fun <T: Any> awaitMeasurement(
 hasReceived: Boolean,
 firstTimeoutMs: Long = 90000,
 idleTimeoutMs: Long = 2000,
 read: suspend () -> T
): T? {
 val value = withTimeoutOrNull(if(hasReceived) idleTimeoutMs else firstTimeoutMs) { read() }
 if(value==null && !hasReceived) throw IOException("Connected, but no measurement arrived before the waiting period ended")
 return value
}
