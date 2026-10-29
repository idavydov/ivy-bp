@file:Suppress("MissingPermission", "DEPRECATION")
package io.github.idavydov.ivybp

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private data class Event(val kind: String, val status: Int = 0, val uuid: UUID? = null, val bytes: ByteArray? = null, val message: String? = null)
class SyncEngine(private val context: Context, private val store: Store) {
 val status = MutableStateFlow("Ready")
 val busy = MutableStateFlow(false)
 init { store.scope.launch { status.collect { android.util.Log.i("IvyBP",it); runCatching { store.journal("status:$it") } } } }
 private val gate=Mutex()
 private val scanGate=Mutex()
 private val manager=context.getSystemService(BluetoothManager::class.java)
 fun sync(address: String) {
  if (!gate.tryLock()) return
  busy.value=true
  store.scope.launch {
   val added=mutableListOf<Reading>()
   var outcome="Saved"
   try {
    val target=Protocol.normalizeAddress(address)
    check(manager.adapter?.isEnabled==true) { "Turn Bluetooth on" }
    withTimeout(180000) {
     try { transfer(target,added) }
     catch (cancel: CancellationException) { throw cancel }
     catch (first: Exception) {
      if (added.isNotEmpty()) throw first
      status.value="Retrying connection…"
      delay(500)
      transfer(target,added)
     }
    }
    status.value=if (added.isEmpty()) "Sync complete · no new readings" else "Saved ${added.size} new reading(s)"
   } catch (e: Exception) {
    outcome=e.message ?: e.javaClass.simpleName
    status.value="Sync stopped: $outcome" + if (added.isEmpty()) "" else " · ${added.size} saved"
   } finally {
    try {
     store.db.records().session(SyncSession(at=System.currentTimeMillis(), added=added.size,outcome=outcome))
     if (added.isNotEmpty() && store.current().notifications) Notices.saved(context,added.maxBy { it.deviceSeconds },added.size)
    } finally { busy.value=false;gate.unlock() }
   }
  }
 }
 private suspend fun transfer(address: String, added: MutableList<Reading>) {
  val events=Channel<Event>(Channel.UNLIMITED)
  val pending=mutableListOf<Event>()
  val callback=object: BluetoothGattCallback() {
   override fun onConnectionStateChange(g: BluetoothGatt,status: Int,newState: Int) {
    android.util.Log.i("IvyBP","GATT state=$newState status=$status")
    events.trySend(Event(if (newState==BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected",status))
   }
   override fun onServicesDiscovered(g: BluetoothGatt,status: Int) { events.trySend(Event("services",status)) }
   override fun onDescriptorWrite(g: BluetoothGatt,d: BluetoothGattDescriptor,status: Int) { events.trySend(Event("descriptor",status)) }
   override fun onCharacteristicWrite(g: BluetoothGatt,c: BluetoothGattCharacteristic,status: Int) { events.trySend(Event("write",status)) }
   private fun receive(uuid: UUID, bytes: ByteArray) {
    try { store.journal("rx:$uuid",bytes); events.trySend(Event("packet",uuid=uuid,bytes=bytes.copyOf())) }
    catch (e: Exception) { events.trySend(Event("error",message="Cannot persist incoming packet: ${e.message}")) }
   }
   override fun onCharacteristicChanged(g: BluetoothGatt,c: BluetoothGattCharacteristic) { if(Build.VERSION.SDK_INT<33) receive(c.uuid,c.value.copyOf()) }
   override fun onCharacteristicChanged(g: BluetoothGatt,c: BluetoothGattCharacteristic,value: ByteArray) { receive(c.uuid,value) }
  }
  suspend fun next(kind: String, uuid: UUID?=null, timeoutMs: Long=12000): Event = awaitGatt(when(kind) { "connected" -> "Bluetooth connection; sync while the monitor Bluetooth symbol blinks"; "services" -> "service discovery"; "descriptor" -> "indication setup"; "write" -> "command acknowledgment"; else -> "monitor $kind" },timeoutMs) {
   val index=pending.indexOfFirst { it.kind==kind && (uuid==null || it.uuid==uuid) }
   if(index>=0) return@awaitGatt pending.removeAt(index)
   while(true) {
    val e=events.receive()
    check(e.kind!="error") { e.message ?: "Bluetooth error" }
    check(e.kind!="disconnected") { "Monitor disconnected (GATT ${e.status})" }
    check(e.status==0) { "GATT ${e.kind} failed (${e.status})" }
    if(e.kind==kind && (uuid==null || e.uuid==uuid)) return@awaitGatt e
    pending.add(e)
   }
   @Suppress("UNREACHABLE_CODE") error("Unreachable")
  }
  status.value="Connecting…"
  val gatt=manager.adapter.getRemoteDevice(Protocol.normalizeAddress(address)).connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)
   ?: error("Cannot create Bluetooth connection")
  try {
   next("connected")
   check(gatt.discoverServices()) { "Cannot discover services" }; next("services")
   val service=gatt.getService(Protocol.service) ?: error("Unsupported monitor: service missing")
   val command=service.getCharacteristic(Protocol.command) ?: error("Command characteristic missing")
   suspend fun subscribeGatt(uuid: UUID) {
    val c=service.getCharacteristic(uuid) ?: error("Indication characteristic missing")
    check(gatt.setCharacteristicNotification(c,true)) { "Cannot enable indications" }
    val descriptor=c.getDescriptor(Protocol.cccd) ?: error("Indication descriptor missing")
    val bytes=BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
    val ok=if(Build.VERSION.SDK_INT>=33) gatt.writeDescriptor(descriptor,bytes)==BluetoothStatusCodes.SUCCESS
     else { descriptor.value=bytes; gatt.writeDescriptor(descriptor) }
    check(ok) { "Cannot queue descriptor write" }; next("descriptor")
   }
   suspend fun writeGatt(bytes: ByteArray) {
    store.journal("tx",bytes)
    val ok=if(Build.VERSION.SDK_INT>=33) gatt.writeCharacteristic(command,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS
     else { command.value=bytes; command.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; gatt.writeCharacteristic(command) }
    check(ok) { "Cannot queue command" }; next("write")
   }
   status.value="Authenticating…"
   Protocol.handshake(object: ProtocolTransport {
    override suspend fun subscribe(uuid: UUID) { subscribeGatt(uuid) }
    override suspend fun challenge() = next("packet",Protocol.reply).bytes!!
    override suspend fun write(bytes: ByteArray) { writeGatt(bytes) }
   })
   status.value="Connected · waiting for measurement to finish…"
   var received=false
   while(true) {
    val event=awaitMeasurement(received) { next("packet",Protocol.data,100000) } ?: break
    status.value="Downloading readings…"
    android.util.Log.i("IvyBP","Measurement packet received (${event.bytes!!.size} bytes)")
    val bytes=event.bytes!!
    val m=Protocol.decode(bytes)
    val r=Reading(device=address.uppercase(),deviceSeconds=m.deviceSeconds,receivedAt=System.currentTimeMillis(),
     systolic=m.systolic,diastolic=m.diastolic,pulse=m.pulse,raw=Protocol.hex(bytes))
    if(store.saveReading(r)) added.add(r)
    received=true
   }
  } finally {
   try { gatt.disconnect() } finally { gatt.close(); events.close() }
  }
 }
 suspend fun editReadings(block: suspend () -> Unit) = gate.withLock {
  busy.value=true
  try { block() } finally { busy.value=false }
 }
 fun scanOnce() {
  if(!scanGate.tryLock()) return
  store.scope.launch {
   try {
    val address=store.current().address
    require(address.isNotEmpty()) { "Choose a monitor first" }
    // Direct GATT connection does not require name/service scan results.
    sync(address)
   } catch(e: Exception) { status.value="Cannot sync: ${e.message ?: "monitor not found"}" }
   finally { scanGate.unlock() }
  }
 }
}
