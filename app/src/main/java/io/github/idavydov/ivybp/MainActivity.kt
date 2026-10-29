@file:Suppress("MissingPermission", "DEPRECATION")
package io.github.idavydov.ivybp

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.bluetooth.BluetoothDevice
import android.companion.*
import android.content.Intent
import android.content.IntentSender
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

class MainActivity: ComponentActivity() {
 private var exportType="csv"
 private var directSetup=false
 private var directVisible by mutableStateOf(false)
 private var directDevices by mutableStateOf<List<Pair<String,String>>>(emptyList())
 private var directMessage by mutableStateOf("")
 private var directAddress by mutableStateOf("")
 private var directSeen by mutableIntStateOf(0)
 private var directScanner: BluetoothLeScanner?=null
 private var directCallback: ScanCallback?=null
 private var directTimeout: Runnable?=null
 private val scanHandler=Handler(Looper.getMainLooper())

 private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
  if(result[Manifest.permission.BLUETOOTH_CONNECT]==true && result[Manifest.permission.BLUETOOTH_SCAN]==true) { if(directSetup) scanDirect() else associate() }
  else ivy.sync.status.value="Nearby devices permission is needed to connect"
 }
 private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
 private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
  if(uri!=null) ivy.store.scope.launch {
   try {
    val text=when(exportType) {
     "json" -> ivy.store.backup()
     "diagnostics" -> java.io.File(noBackupFilesDir,"ble.jsonl").let { if(it.exists()) it.readText() else "No diagnostics yet" }
     else -> ivy.store.csv()
    }
    contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(text) }
    ivy.sync.status.value="Export saved"
   } catch(e: Exception) { ivy.sync.status.value="Export failed: ${e.message}" }
  }
 }
 private val restore=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if(uri!=null) ivy.store.scope.launch {
   try {
    // Read and validate before any database changes.
    val bytes=contentResolver.openInputStream(uri)!!.use { input ->
     val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
     while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=25*1024*1024) { "Backup exceeds 25 MB" }; out.write(buffer,0,n) }; out.toByteArray() }
    require(bytes.size<=25*1024*1024) { "Backup exceeds 25 MB" }
    stopService(Intent(this@MainActivity,ListeningService::class.java))
    val old=ivy.store.current()
    Association.observe(this@MainActivity,old.address,false)
    val count=ivy.store.restore(bytes.toString(Charsets.UTF_8))
    ivy.sync.status.value="Restored $count new readings · enable Auto-sync when ready"
   } catch(e: Exception) { ivy.sync.status.value="Restore failed: ${e.message}" }
  }
 }
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xff176b60))) { Surface(Modifier.fillMaxSize()) { App() } } }
 }
 override fun onResume() {
  super.onResume()
  ivy.store.scope.launch {
   val o=ivy.store.current()
   if(o.auto) {
    try {
     if(o.reliable || Association.valid(this@MainActivity,o.address)) {
      Association.observe(this@MainActivity,o.address,true)
      if(o.reliable) withContext(Dispatchers.Main) { startForegroundService(Intent(this@MainActivity,ListeningService::class.java)) }
     }
     else ivy.store.configure(o.copy(auto=false,reliable=false))
    } catch(e: Exception) { ivy.sync.status.value="Auto-sync needs setup: ${e.message}" }
   }
  }
 }
 private fun setup(direct: Boolean=false) {
  directSetup=direct
  permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT))
 }
 private fun stopDirectScan() {
  directTimeout?.let { scanHandler.removeCallbacks(it) }; directTimeout=null
  try { directCallback?.let { directScanner?.stopScan(it) } } catch(_: SecurityException) {}
  directCallback=null; directScanner=null
 }
 override fun onDestroy() { stopDirectScan(); super.onDestroy() }
 private fun scanDirect() {
  stopDirectScan()
  directDevices=emptyList();directSeen=0;directAddress=ivy.store.options.value.address;directMessage="Searching… Take a measurement; keep this open while Bluetooth blinks.";directVisible=true
  try {
   directScanner=getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner ?: error("Turn Bluetooth on")
   directCallback=object: ScanCallback() {
    override fun onScanResult(type: Int,result: ScanResult) {
     runOnUiThread { directSeen++ }
     val record=result.scanRecord
     val name=record?.deviceName ?: result.device.name ?: ""
     if(Protocol.matchesAdvertisement(name,record?.serviceUuids?.map { it.uuid } ?: emptyList())) runOnUiThread {
      directDevices=(directDevices+Pair(result.device.address,name.ifBlank { "IVY monitor" })).distinctBy { it.first }
     }
    }
    override fun onScanFailed(errorCode: Int) { runOnUiThread { directMessage="Bluetooth scan failed ($errorCode). Close this dialog and retry.";stopDirectScan() } }
   }
   directScanner!!.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),directCallback!!)
   directTimeout=Runnable { stopDirectScan();directMessage=if(directDevices.isEmpty()) "No monitor found. Retry during or immediately after a measurement." else "Select your monitor below." }.also { scanHandler.postDelayed(it,30000) }
  } catch(e: Exception) { directMessage=e.message ?: "Cannot scan";stopDirectScan() }
 }
 private fun selectedDirect(address: String) {
  stopDirectScan();directVisible=false
  ivy.store.scope.launch {
   try {
    val old=ivy.store.current()
    stopService(Intent(this@MainActivity,ListeningService::class.java))
    Association.observe(this@MainActivity,old.address,false)
    ivy.store.configure(old.copy(address=address,auto=false,reliable=false))
    ivy.sync.status.value="Monitor saved · connecting directly"
    ivy.sync.sync(address)
   } catch(e: Exception) { ivy.sync.status.value="Setup failed: ${e.message}" }
  }
 }
 private fun associate() {
  try {
   val cdm=getSystemService(CompanionDeviceManager::class.java) ?: error("Companion setup unavailable")
   val filter=BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("B810A0.*")).build()
   cdm.associate(AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build(),object: CompanionDeviceManager.Callback() {
    override fun onDeviceFound(launcher: IntentSender) { launchChooser(launcher) }
    override fun onAssociationPending(launcher: IntentSender) { launchChooser(launcher) }
    @android.annotation.TargetApi(33)
    override fun onAssociationCreated(info: AssociationInfo) { info.deviceMacAddress?.toString()?.let { selected(it) } }
    override fun onFailure(error: CharSequence?) { ivy.sync.status.value="Setup failed: ${error ?: "no monitor found"}" }
   },Handler(Looper.getMainLooper()))
  } catch(e: Exception) { ivy.sync.status.value="Setup unavailable: ${e.message}" }
 }
 private fun launchChooser(sender: IntentSender) { runOnUiThread {
  try { startIntentSenderForResult(sender,10,null,0,0,0) } catch(e: Exception) { ivy.sync.status.value="Setup failed: ${e.message}" }
 } }
 override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==10 && resultCode!=Activity.RESULT_OK) ivy.sync.status.value="System search canceled · try Find monitor directly"
  if(requestCode==10 && resultCode==Activity.RESULT_OK && Build.VERSION.SDK_INT<33) {
   val device=data?.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)
   when(device) { is ScanResult -> selected(device.device.address); is BluetoothDevice -> selected(device.address) }
  }
 }
 private fun selected(address: String) { ivy.store.scope.launch {
  val old=ivy.store.current()
  try {
   stopService(Intent(this@MainActivity,ListeningService::class.java))
   if(old.address.isNotBlank()) Association.observe(this@MainActivity,old.address,false)
   ivy.store.configure(old.copy(address=address,auto=true,reliable=false))
   Association.observe(this@MainActivity,address,true)
   ivy.sync.status.value="Monitor ready · syncing now"
   ivy.sync.sync(address)
   runOnUiThread { if(Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
  } catch(e: Exception) { ivy.sync.status.value="Setup failed: ${e.message}" }
 } }
 private fun configure(o: Options) { ivy.store.scope.launch {
  try {
   if(o.auto) check(o.address.isNotBlank() && (o.reliable || Association.valid(this@MainActivity,o.address))) { "For direct Bluetooth setup, enable Reliable listening to collect in the background" }
   Association.observe(this@MainActivity,o.address,o.auto)
   ivy.store.configure(o)
   withContext(Dispatchers.Main) {
    if(o.auto && o.reliable) startForegroundService(Intent(this@MainActivity,ListeningService::class.java))
    else stopService(Intent(this@MainActivity,ListeningService::class.java))
   }
   if(o.notifications && Build.VERSION.SDK_INT>=33) withContext(Dispatchers.Main) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
  } catch(e: Exception) { ivy.sync.status.value="Settings failed: ${e.message}" }
 } }
 @Composable private fun App() {
  val readings by ivy.store.db.records().observe().collectAsStateWithLifecycle(emptyList())
  val last by ivy.store.db.records().lastSession().collectAsStateWithLifecycle(null)
  val options by ivy.store.options.collectAsStateWithLifecycle()
  val status by ivy.sync.status.collectAsStateWithLifecycle()
  val busy by ivy.sync.busy.collectAsStateWithLifecycle()
  var tab by remember { mutableIntStateOf(if(intent.getBooleanExtra("history",false)) 1 else 0) }
  var range by remember { mutableIntStateOf(30) }
  var restoreConfirm by remember { mutableStateOf(false) }
  var deleteAllConfirm by remember { mutableStateOf(false) }
  var deleteReadingConfirm by remember { mutableStateOf<Reading?>(null) }
  var deleting by remember { mutableStateOf(false) }
  val uiScope=rememberCoroutineScope()
  fun delete(all: Boolean, reading: Reading?=null) {
   deleting=true
   uiScope.launch {
    try {
     withContext(Dispatchers.IO) {
      ivy.sync.editReadings { if(all) ivy.store.deleteAllReadings() else ivy.store.deleteReading(reading!!.id) }
     }
     ivy.sync.status.value=if(all) "All saved readings deleted" else "Reading deleted"
    } catch(e: Exception) { ivy.sync.status.value="Delete failed: ${e.message}" }
    finally { deleting=false }
   }
  }
  Scaffold(bottomBar={ NavigationBar {
   listOf("Home","History","Settings").forEachIndexed { i,label ->
    NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(painterResource(listOf(R.drawable.ic_home,R.drawable.ic_history,R.drawable.ic_settings)[i]),contentDescription=null,modifier=Modifier.size(32.dp))},label={Text(label)})
   }
  } }) { padding ->
   LazyColumn(Modifier.padding(padding).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Spacer(Modifier.height(8.dp)); Text("IVY BP",style=MaterialTheme.typography.headlineLarge); Text(status,style=MaterialTheme.typography.bodyMedium) }
    if(tab==0) {
     item {
      ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp)) {
       Text("LATEST MEASUREMENT",style=MaterialTheme.typography.labelMedium)
       val r=readings.firstOrNull()
       Text(r?.let { "${it.systolic}/${it.diastolic}" } ?: "— / —",style=MaterialTheme.typography.displayLarge)
       Text(r?.let { "mmHg   ·   Pulse ${it.pulse} bpm" } ?: "Your readings will appear here")
       if(r!=null) { Text(Protocol.deviceTime(r.deviceSeconds).replace('T',' '));  }
      } }
     }
     item { Button(onClick={ivy.sync.scanOnce()},enabled=options.address.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text("Sync now") } }
     item { Toggle("Auto-sync",options.auto) { configure(options.copy(auto=it,reliable=if(it) options.reliable else false)) } }
     item { Text("Take a measurement while the phone is nearby. Sync while the monitor’s Bluetooth symbol blinks. MEM alone may not enable Bluetooth.") }
     item { last?.let { Text("Last session: ${Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM HH:mm"))} · ${it.added} added · ${it.outcome}") } }
     if(options.address.isBlank()) item { Button(onClick={setup()}) { Text("Choose monitor") }; OutlinedButton(onClick={setup(true)}) { Text("Find monitor directly") } }
    } else if(tab==1) {
     item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(7,30,0).forEach { d ->
      FilterChip(selected=range==d,onClick={range=d},label={Text(if(d==0) "All" else "$d days")})
     } } }
     val cutoff=LocalDateTime.now().minusDays(range.toLong())
     val visible=readings.filter { range==0 || LocalDateTime.of(2010,1,1,0,0).plusSeconds(it.deviceSeconds)>=cutoff }
     item { Trend(visible) }
     if(visible.isEmpty()) item { Text("No readings in this period.") }
     items(visible,key={it.id}) { r ->
      val date=LocalDateTime.of(2010,1,1,0,0).plusSeconds(r.deviceSeconds)
      val stamp=date.format(DateTimeFormatter.ofPattern(if(date.year==LocalDate.now().year) "dd MMM HH:mm" else "dd MMM yy HH:mm"))
      Card(Modifier.fillMaxWidth()) {
       Row(Modifier.fillMaxWidth().padding(start=12.dp,end=2.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("${r.systolic}/${r.diastolic} mmHg · ${r.pulse} bpm",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(stamp,style=MaterialTheme.typography.labelSmall,maxLines=1)
        IconButton(onClick={deleteReadingConfirm=r},enabled=!busy && !deleting) {
         Icon(painterResource(R.drawable.ic_delete),contentDescription="Delete reading from $stamp",modifier=Modifier.size(20.dp))
        }
       }
      }
     }
    } else {
     item { Text("MONITOR",style=MaterialTheme.typography.labelLarge); Text(options.address.ifBlank { "No monitor selected" }); Button(onClick={setup()}) { Text("Choose monitor") }; OutlinedButton(onClick={setup(true)}) { Text("Find monitor directly") } }
     item { Toggle("Auto-sync",options.auto) { configure(options.copy(auto=it,reliable=if(it) options.reliable else false)) } }
     item { Toggle("Reliable listening",options.reliable) { configure(options.copy(reliable=it,auto=if(it) true else options.auto)) }; Text("Optional ongoing notification. Use if default background collection misses readings.",style=MaterialTheme.typography.bodySmall) }
     item { Toggle("Measurement saved notifications",options.notifications) { configure(options.copy(notifications=it)) } }
     item { Text("BACKUP & EXPORT",style=MaterialTheme.typography.labelLarge); Text("Android backs up history and preferences when system backup is enabled. Android controls the schedule. Restore requires monitor setup again.") }
     item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick={exportType="csv";export.launch("ivy-bp.csv")}) { Text("CSV") }
      OutlinedButton(onClick={exportType="json";export.launch("ivy-bp-backup.json")}) { Text("Backup") }
      OutlinedButton(onClick={restoreConfirm=true}) { Text("Restore") }
     } }
     item { OutlinedButton(onClick={exportType="diagnostics";export.launch("ivy-bp-diagnostics.jsonl")}) { Text("Export diagnostics") }; Text("Diagnostics include raw monitor packets and health data. Share only when intended.",style=MaterialTheme.typography.bodySmall) }
     item { OutlinedButton(onClick={deleteAllConfirm=true},enabled=readings.isNotEmpty() && !busy && !deleting,colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Delete all saved readings") } }
     item { Text("IVY BP ${packageManager.getPackageInfo(packageName,0).versionName} · MIT · Independent companion app. No account or server.") }
    }
    item { Spacer(Modifier.height(16.dp)) }
   }
  }
  if(deleteAllConfirm || deleteReadingConfirm!=null) {
   val all=deleteAllConfirm
   val reading=deleteReadingConfirm
   AlertDialog(onDismissRequest={deleteAllConfirm=false;deleteReadingConfirm=null},
    title={Text(if(all) "Delete all saved readings?" else "Delete this reading?")},
    text={Text(if(all) "Delete ${readings.size} saved readings and local diagnostic logs? Monitor setup stays. Existing backup files are unchanged."
     else "${reading!!.systolic}/${reading.diastolic} mmHg · ${reading.pulse} bpm. This also clears local diagnostic logs. Existing backup files are unchanged.")},
    confirmButton={TextButton(enabled=!busy && !deleting,onClick={deleteAllConfirm=false;deleteReadingConfirm=null;delete(all,reading)}) { Text("Delete",color=MaterialTheme.colorScheme.error) }},
    dismissButton={TextButton(onClick={deleteAllConfirm=false;deleteReadingConfirm=null}) { Text("Cancel") }})
  }
  if(directVisible) AlertDialog(onDismissRequest={stopDirectScan();directVisible=false},
   title={Text("Find IVY monitor")},text={ Column {
    Text(directMessage)
    Text("BLE advertisements received: $directSeen",style=MaterialTheme.typography.bodySmall)
    directDevices.forEach { (address,name) -> TextButton(onClick={selectedDirect(address)}) { Text("$name\n$address") } }
    OutlinedTextField(value=directAddress,onValueChange={directAddress=it.uppercase().trim()},label={Text("Bluetooth address")},placeholder={Text("AA:BB:CC:DD:EE:FF")},singleLine=true,modifier=Modifier.fillMaxWidth())
    TextButton(enabled=directAddress.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")),onClick={selectedDirect(directAddress)}) { Text("Connect by address") }
    Text("Direct setup supports Sync now and Reliable listening. System companion setup is needed for background collection without an ongoing notification.",style=MaterialTheme.typography.bodySmall)
   } },confirmButton={TextButton(onClick={scanDirect()}) { Text("Retry") }},
   dismissButton={TextButton(onClick={stopDirectScan();directVisible=false}) { Text("Cancel") }})
  if(restoreConfirm) AlertDialog(onDismissRequest={restoreConfirm=false},title={Text("Restore backup?")},text={Text("Readings will be merged without duplicates. Automatic collection will stop until you enable it again.")},confirmButton={TextButton(onClick={restoreConfirm=false;restore.launch(arrayOf("application/json","application/octet-stream","text/plain"))}) { Text("Choose file") }},dismissButton={TextButton(onClick={restoreConfirm=false}) { Text("Cancel") }})
 }
}
@Composable private fun Toggle(label: String,value: Boolean,onChange: (Boolean)->Unit) {
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,Modifier.weight(1f).padding(top=12.dp)); Switch(checked=value,onCheckedChange=onChange) }
}
@Composable private fun Trend(readings: List<Reading>) {
 val sorted=readings.sortedBy { it.deviceSeconds }
 val scale=pressureScale(sorted.flatMap { listOf(it.systolic,it.diastolic) })
 val density=LocalDensity.current
 val textSize=with(density) { 11.sp.toPx() }
 val axisColor=MaterialTheme.colorScheme.onSurfaceVariant
 val gridColor=MaterialTheme.colorScheme.outlineVariant
 val description=if(sorted.isEmpty()) "No readings to chart" else "Blood pressure in millimeters of mercury, ${scale.min} to ${scale.max}. ${sorted.size} readings; exact values in the list below."
 Column {
  Text("Pressure (mmHg)",style=MaterialTheme.typography.labelMedium)
  Canvas(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription=description }) {
   val left=44.dp.toPx(); val right=size.width-10.dp.toPx()
   val top=10.dp.toPx(); val bottom=size.height-12.dp.toPx()
   val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    this.textSize=textSize; color=android.graphics.Color.argb(255,(axisColor.red*255).toInt(),(axisColor.green*255).toInt(),(axisColor.blue*255).toInt()); textAlign=android.graphics.Paint.Align.RIGHT
   }
   fun y(value: Int)=bottom-(value-scale.min).toFloat()/(scale.max-scale.min)*(bottom-top)
   scale.ticks.forEach { tick ->
    val py=y(tick)
    drawLine(gridColor,androidx.compose.ui.geometry.Offset(left,py),androidx.compose.ui.geometry.Offset(right,py),1.dp.toPx())
    drawContext.canvas.nativeCanvas.drawText(tick.toString(),left-7.dp.toPx(),py-(paint.ascent()+paint.descent())/2,paint)
   }
   drawLine(axisColor,androidx.compose.ui.geometry.Offset(left,top),androidx.compose.ui.geometry.Offset(left,bottom),1.dp.toPx())
   if(sorted.isNotEmpty()) {
    val first=sorted.first().deviceSeconds; val span=(sorted.last().deviceSeconds-first).coerceAtLeast(1)
    fun x(r: Reading)=left+4.dp.toPx()+(r.deviceSeconds-first).toFloat()/span*(right-left-8.dp.toPx())
    listOf(true,false).forEach { sys ->
     val color=if(sys) Color(0xff176b60) else Color(0xffbd654f)
     val path=Path()
     sorted.forEachIndexed { i,r ->
      val px=x(r); val py=y(if(sys) r.systolic else r.diastolic)
      if(i==0) path.moveTo(px,py) else path.lineTo(px,py)
      drawCircle(color,3.dp.toPx(),androidx.compose.ui.geometry.Offset(px,py))
     }
     drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
   }
  }
 }
}
