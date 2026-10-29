@file:Suppress("MissingPermission", "DEPRECATION")
package io.github.idavydov.ivybp

import android.app.*
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.companion.*
import android.content.*
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicBoolean

object Association {
 fun valid(context: Context,address: String): Boolean {
  val cdm=context.getSystemService(CompanionDeviceManager::class.java)
  return cdm?.associations?.any { it.equals(address,true) }==true
 }
 fun observe(context: Context,address: String,enable: Boolean) {
  val canonical=Protocol.savedAddress(address)
  if(!valid(context,canonical)) return
  val cdm=context.getSystemService(CompanionDeviceManager::class.java)
  if(Build.VERSION.SDK_INT>=36) {
   val info=cdm.myAssociations.first { it.deviceMacAddress?.toString().equals(canonical,true) }
   val request=ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
   if(enable) cdm.startObservingDevicePresence(request) else cdm.stopObservingDevicePresence(request)
  } else if(enable) cdm.startObservingDevicePresence(canonical) else cdm.stopObservingDevicePresence(canonical)
 }
}
class PresenceService: CompanionDeviceService() {
 private fun appeared(address: String) { android.util.Log.i("IvyBP","Companion device appeared"); ivy.store.scope.launch {
  val o=ivy.store.current()
  if(o.auto && o.address.equals(address,true) && Association.valid(this@PresenceService,address)) ivy.sync.sync(address)
 } }
 override fun onDeviceAppeared(address: String) { appeared(address) }
 @android.annotation.TargetApi(33)
 override fun onDeviceAppeared(associationInfo: AssociationInfo) { associationInfo.deviceMacAddress?.toString()?.let(::appeared) }
 @android.annotation.TargetApi(36)
 override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
  android.util.Log.i("IvyBP","Companion presence event=${event.event}")
  if(event.event==DevicePresenceEvent.EVENT_BLE_APPEARED || event.event==DevicePresenceEvent.EVENT_BT_CONNECTED) {
   getSystemService(CompanionDeviceManager::class.java).myAssociations.firstOrNull { it.id==event.associationId }?.deviceMacAddress?.toString()?.let(::appeared)
  }
 }
}
object Notices {
 fun channels(c: Context) {
  c.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
   NotificationChannel("listening","Background listening",NotificationManager.IMPORTANCE_LOW),
   NotificationChannel("readings","Measurements saved",NotificationManager.IMPORTANCE_DEFAULT)))
 }
 private fun open(c: Context)=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java).putExtra("history",true),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
 fun listening(c: Context): Notification {
  val stop=PendingIntent.getService(c,1,Intent(c,ListeningService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(c,"listening").setSmallIcon(R.drawable.ic_monitor).setContentTitle("IVY BP listening")
   .setContentText("Waiting for your monitor").setOngoing(true).setContentIntent(open(c)).addAction(0,"Stop",stop).build()
 }
 fun saved(c: Context,r: Reading,count: Int) {
  val n=Notification.Builder(c,"readings").setSmallIcon(R.drawable.ic_monitor).setContentTitle("Measurement saved")
   .setContentText("${r.systolic}/${r.diastolic} mmHg · pulse ${r.pulse}" + if(count>1) " · $count new readings" else "")
   .setContentIntent(open(c)).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
  try { c.getSystemService(NotificationManager::class.java).notify(2,n) } catch(_: SecurityException) { }
 }
}
class ListeningService: Service() {
 private var lastDispatch=0L
 private var scanning=false
 private var selectedAddress=""
 private var busyWatcher: Job?=null
 private val power=object: BroadcastReceiver() {
  override fun onReceive(c: Context,i: Intent) {
   when(i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,-1)) {
    android.bluetooth.BluetoothAdapter.STATE_OFF -> { pauseScan();callback=null; scanner=null; ivy.sync.status.value="Bluetooth is off" }
    android.bluetooth.BluetoothAdapter.STATE_ON -> onStartCommand(Intent(this@ListeningService,ListeningService::class.java),0,0)
   }
  }
 }
 override fun onCreate() {
  super.onCreate()
  busyWatcher=ivy.store.scope.launch(Dispatchers.Main) {
   ivy.sync.busy.collectLatest { busy ->
    if(busy) pauseScan()
    else {
     // Avoid immediate duplicate reconnects after the completed measurement window.
     delay(10000)
     if(callback!=null && ivy.store.options.value.auto && ivy.store.options.value.reliable) {
      try { resumeScan() } catch(e: Exception) { ivy.sync.status.value="Listening stopped: ${e.message}";stopSelf() }
     }
    }
   }
  }
  val filter=IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
  if(Build.VERSION.SDK_INT>=33) registerReceiver(power,filter,Context.RECEIVER_EXPORTED) else registerReceiver(power,filter)
 }
 private var scanner: android.bluetooth.le.BluetoothLeScanner?=null
 private var callback: ScanCallback?=null
 override fun onBind(intent: Intent?): IBinder?=null
 override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
  if(intent?.action=="stop") {
   ivy.store.scope.launch { ivy.store.configure(ivy.store.options.value.copy(reliable=false)) }
   stopSelf(); return START_NOT_STICKY
  }
  startForeground(1,Notices.listening(this))
  ivy.store.scope.launch(Dispatchers.Main) {
   try {
    val o=ivy.store.current()
    check(o.auto && o.reliable && o.address.isNotBlank()) { "Choose a monitor and enable Reliable listening first" }
    if(callback==null) {
     scanner=getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner ?: error("Turn Bluetooth on")
     callback=object: ScanCallback() {
      override fun onScanResult(type: Int,result: ScanResult) {
       val now=android.os.SystemClock.elapsedRealtime()
       if(ivy.store.options.value.auto && now-lastDispatch>10000) { lastDispatch=now; ivy.sync.sync(o.address) }
      }
      override fun onScanFailed(code: Int) { ivy.sync.status.value="Background scan failed ($code)"; stopSelf() }
     }
     selectedAddress=o.address
     resumeScan()
    }
   } catch(e: Exception) { ivy.sync.status.value="Listening stopped: ${e.message}"; stopSelf() }
  }
  return START_NOT_STICKY
 }
 private fun pauseScan() {
  if(scanning) try { callback?.let { scanner?.stopScan(it) } } catch(_: SecurityException) {}
  scanning=false
 }
 private fun resumeScan() {
  if(scanning || ivy.sync.busy.value || callback==null) return
  scanner!!.startScan(listOf(ScanFilter.Builder().setDeviceAddress(selectedAddress).build()),
   ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),callback!!)
  scanning=true
  android.util.Log.i("IvyBP","Reliable low-power listener active")
 }
 override fun onDestroy() {
  busyWatcher?.cancel()
  pauseScan()
  unregisterReceiver(power)
  callback=null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
 }
}
