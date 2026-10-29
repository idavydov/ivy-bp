package io.github.idavydov.ivybp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
@RunWith(AndroidJUnit4::class)
class BackupTest {
 private lateinit var store: Store
 @Before fun init() { store=InstrumentationRegistry.getInstrumentation().targetContext.ivy.store; store.db.clearAllTables() }
 private fun backup(raw: String="1e7b004e00000080510100410000000000")=JSONObject()
  .put("format","ivy-bp").put("version",1).put("readings",JSONArray().put(JSONObject()
   .put("device","00:11:22:33:44:55").put("deviceSeconds",86400).put("receivedAt",1000).put("raw",raw))).toString()
 private fun reading(seconds: Long,pulse: Int=65): Reading {
  val bytes=java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
   .put(0x1e).putShort(123).putShort(78).putShort(0).putInt(seconds.toInt()).putShort(pulse.toShort()).put(byteArrayOf(0,0,0)).array()
  return Reading(device="00:11:22:33:44:55",deviceSeconds=seconds,receivedAt=1000,systolic=123,diastolic=78,pulse=pulse,raw=Protocol.hex(bytes))
 }
 @Test fun individualDeletionPreservesOthersAndSuppressesRedownload() = runBlocking {
  val first=reading(86400);val second=reading(86460,66)
  assertTrue(store.saveReading(first)); assertTrue(store.saveReading(second))
  val rows=store.db.records().all()
  store.deleteReading(rows.first().id)
  assertEquals(1,store.db.records().all().size)
  assertEquals(66,store.db.records().all().single().pulse)
  assertFalse(store.saveReading(first))
  val old=backup()
  assertEquals(1,store.restore(old)) // Explicit restore may recover the deleted record.
  assertEquals(2,store.db.records().all().size)
 }
 @Test fun deleteAllPreservesSetupAndCarriesDeletionMarkersInBackup() = runBlocking {
  store.configure(Options(address="00:11:22:33:44:55",auto=true,reliable=true))
  val before=store.current()
  val r=reading(86400);assertTrue(store.saveReading(r))
  store.deleteAllReadings()
  assertTrue(store.db.records().all().isEmpty())
  assertEquals(before,store.current())
  assertFalse(store.saveReading(r))
  val file=store.backup(); assertEquals(1,JSONObject(file).getJSONArray("deletedFingerprints").length())
  store.db.clearAllTables();store.restore(file)
  assertFalse(store.saveReading(r))
 }
 @Test fun mutationGateSerializesOperationsAndAlwaysReleases() = runBlocking {
  val sync=InstrumentationRegistry.getInstrumentation().targetContext.ivy.sync
  try { sync.editReadings { assertTrue(sync.busy.value); error("simulated failure") } } catch(_: IllegalStateException) {}
  assertFalse(sync.busy.value)
  sync.editReadings { assertTrue(sync.busy.value) }
  assertFalse(sync.busy.value)
 }
 @Test fun mergeRoundTripAndDeduplication()= runBlocking {
  assertEquals(1,store.restore(backup()))
  assertEquals(0,store.restore(backup()))
  assertEquals(1,store.db.records().all().size)
  val exported=store.backup()
  store.db.clearAllTables()
  assertEquals(1,store.restore(exported))
  assertFalse(store.current().auto)
  assertTrue(store.csv().contains("123,78,65"))
 }
 @Test fun invalidBackupDoesNotPartiallyImport()= runBlocking {
  val root=JSONObject(backup())
  root.getJSONArray("readings").put(JSONObject().put("raw","invalid"))
  try { store.restore(root.toString()); fail("Invalid file accepted") } catch(_: Exception) {}
  assertTrue(store.db.records().all().isEmpty())
 }
}
