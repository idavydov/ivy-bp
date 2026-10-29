package io.github.idavydov.ivybp

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

@Entity(indices=[Index(value=["device","deviceSeconds","systolic","diastolic","pulse"], unique=true)])
data class Reading(@PrimaryKey(autoGenerate=true) val id: Long = 0, val device: String,
 val deviceSeconds: Long, val receivedAt: Long, val systolic: Int, val diastolic: Int,
 val pulse: Int, val raw: String, val timeVerified: Boolean = false)
@Entity
data class Deletion(@PrimaryKey val fingerprint: String)
@Entity
data class SyncSession(@PrimaryKey(autoGenerate=true) val id: Long = 0, val at: Long, val added: Int, val outcome: String)
@Dao interface Records {
 @Query("SELECT * FROM Reading ORDER BY deviceSeconds DESC, id DESC") fun observe(): Flow<List<Reading>>
 @Query("SELECT * FROM Reading ORDER BY deviceSeconds") suspend fun all(): List<Reading>
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(reading: Reading): Long
 @Query("SELECT * FROM Reading WHERE id=:id") suspend fun find(id: Long): Reading?
 @Query("DELETE FROM Reading WHERE id=:id") suspend fun delete(id: Long)
 @Query("DELETE FROM Reading") suspend fun deleteAll()
 @Query("DELETE FROM SyncSession") suspend fun deleteSessions()
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun suppress(marker: Deletion)
 @Query("SELECT EXISTS(SELECT 1 FROM Deletion WHERE fingerprint=:key)") suspend fun suppressed(key: String): Boolean
 @Query("SELECT fingerprint FROM Deletion") suspend fun deletedKeys(): List<String>
 @Query("DELETE FROM Deletion WHERE fingerprint=:key") suspend fun unsuppress(key: String)
 @Insert suspend fun session(session: SyncSession)
 @Query("SELECT * FROM SyncSession ORDER BY id DESC LIMIT 1") fun lastSession(): Flow<SyncSession?>
}
@androidx.room.Database(entities=[Reading::class, SyncSession::class, Deletion::class], version=2, exportSchema=false)
abstract class Database: RoomDatabase() { abstract fun records(): Records }
val MIGRATION_1_2=object: androidx.room.migration.Migration(1,2) {
 override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
  db.execSQL("CREATE TABLE IF NOT EXISTS Deletion (fingerprint TEXT NOT NULL, PRIMARY KEY(fingerprint))")
 }
}
fun Reading.fingerprint(): String {
 val identity="${Protocol.normalizeAddress(device)}:$deviceSeconds:$systolic:$diastolic:$pulse"
 return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
val Context.preferences by preferencesDataStore("preferences")
data class Options(val address: String = "", val auto: Boolean = false, val notifications: Boolean = true, val reliable: Boolean = false)
class Store(val context: Context) {
 val db = Room.databaseBuilder(context, Database::class.java,"ivy.db").addMigrations(MIGRATION_1_2).build()
 val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
 private val address = stringPreferencesKey("device")
 private val auto = booleanPreferencesKey("auto")
 private val notices = booleanPreferencesKey("notices")
 private val reliable = booleanPreferencesKey("reliable")
 val options = context.preferences.data.map { Options(Protocol.savedAddress(it[address] ?: ""), it[auto] ?: false, it[notices] ?: true, it[reliable] ?: false) }
  .stateIn(scope, SharingStarted.Eagerly, Options())
 suspend fun current(): Options { val p=context.preferences.data.first(); return Options(Protocol.savedAddress(p[address] ?: ""),p[auto] ?: false,p[notices] ?: true,p[reliable] ?: false) }
 suspend fun configure(value: Options) { context.preferences.edit {
  it[address]=Protocol.savedAddress(value.address); it[auto]=value.auto; it[notices]=value.notifications; it[reliable]=value.reliable
 } }
 fun journal(event: String, bytes: ByteArray? = null) {
  // Not backed up: exact packets survive process failure independently of parsing.
  val file = File(context.noBackupFilesDir, "ble.jsonl")
  if (file.length() > 1024*1024) file.renameTo(File(context.noBackupFilesDir, "ble.previous.jsonl"))
  val row = JSONObject().put("at",System.currentTimeMillis()).put("event",event)
  if (bytes != null) row.put("hex",Protocol.hex(bytes))
  FileOutputStream(file,true).use { it.write((row.toString()+"\n").toByteArray()); it.fd.sync() }
 }
 suspend fun saveReading(reading: Reading): Boolean = db.withTransaction {
  if(db.records().suppressed(reading.fingerprint())) false else db.records().insert(reading)!=-1L
 }
 private fun clearDiagnostics() {
  listOf("ble.jsonl","ble.previous.jsonl").forEach { File(context.noBackupFilesDir,it).delete() }
 }
 suspend fun deleteReading(id: Long) {
  db.withTransaction {
   db.records().find(id)?.let { db.records().suppress(Deletion(it.fingerprint()));db.records().delete(id) }
  }
  clearDiagnostics()
 }
 suspend fun deleteAllReadings() {
  db.withTransaction {
   db.records().all().forEach { db.records().suppress(Deletion(it.fingerprint())) }
   db.records().deleteAll(); db.records().deleteSessions()
  }
  clearDiagnostics()
 }
 suspend fun backup(): String {
  val rows = JSONArray()
  val deleted=JSONArray()
  db.withTransaction { db.records().all().forEach { r -> rows.put(JSONObject()
   .put("device",r.device).put("deviceSeconds",r.deviceSeconds).put("receivedAt",r.receivedAt)
   .put("raw",r.raw).put("timeVerified",r.timeVerified)) }; db.records().deletedKeys().forEach { deleted.put(it) } }
  return JSONObject().put("format","ivy-bp").put("version",1).put("readings",rows).put("deletedFingerprints",deleted)
   .put("preferences",JSONObject().put("notifications",current().notifications)).toString(2)
 }
 suspend fun restore(text: String): Int {
  val root=JSONObject(text)
  require(root.getString("format")=="ivy-bp" && root.getInt("version")==1) { "Unsupported backup version" }
  val array=root.getJSONArray("readings")
  require(array.length() <= 100000) { "Backup too large" }
  val rows=(0 until array.length()).map { i ->
   val o=array.getJSONObject(i); val raw=o.getString("raw"); val m=Protocol.decode(Protocol.unhex(raw))
   val device=o.getString("device")
   require(device.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) { "Invalid device address" }
   require(o.getLong("deviceSeconds")==m.deviceSeconds && o.getLong("receivedAt")>=0) { "Invalid backup reading" }
   Reading(device=device.uppercase(), deviceSeconds=m.deviceSeconds,receivedAt=o.getLong("receivedAt"),
    systolic=m.systolic,diastolic=m.diastolic,pulse=m.pulse,raw=raw.lowercase(),timeVerified=o.optBoolean("timeVerified",false))
  }
  val markers=root.optJSONArray("deletedFingerprints") ?: JSONArray()
  require(markers.length()<=100000) { "Backup too large" }
  val keys=(0 until markers.length()).map { markers.getString(it).also { key ->
   require(key.matches(Regex("[0-9a-f]{64}"))) { "Invalid deletion marker" }
  } }.toSet()
  require(rows.none { it.fingerprint() in keys }) { "Backup has contradictory deleted readings" }
  val notify=root.optJSONObject("preferences")?.optBoolean("notifications",true) ?: true
  val count=db.withTransaction {
   val existing=db.records().all().map { it.fingerprint() }.toSet()
   keys.filterNot { it in existing }.forEach { db.records().suppress(Deletion(it)) }
   // Explicit restore can recover a reading deleted since this backup was made.
   rows.count { db.records().unsuppress(it.fingerprint()); db.records().insert(it)!=-1L }
  }
  configure(current().copy(auto=false,reliable=false,notifications=notify))
  return count
 }
 suspend fun csv(): String = buildString {
  appendLine("device_time,systolic_mmhg,diastolic_mmhg,pulse_bpm,received_at_epoch_ms,timestamp_verified")
  db.records().all().forEach { appendLine("${Protocol.deviceTime(it.deviceSeconds)},${it.systolic},${it.diastolic},${it.pulse},${it.receivedAt},${it.timeVerified}") }
 }
}
class IvyApp: Application() {
 lateinit var store: Store
 lateinit var sync: SyncEngine
 override fun onCreate() { super.onCreate(); store=Store(this); sync=SyncEngine(this,store); Notices.channels(this) }
}
val Context.ivy: IvyApp get() = applicationContext as IvyApp
