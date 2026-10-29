package io.github.idavydov.ivybp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class MigrationTest {
 @Test fun existingVersionOneReadingsSurviveMigration() = runBlocking {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val name="migration-test.db"; c.deleteDatabase(name)
  c.openOrCreateDatabase(name,0,null).use { db ->
   db.execSQL("CREATE TABLE Reading (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, device TEXT NOT NULL, deviceSeconds INTEGER NOT NULL, receivedAt INTEGER NOT NULL, systolic INTEGER NOT NULL, diastolic INTEGER NOT NULL, pulse INTEGER NOT NULL, raw TEXT NOT NULL, timeVerified INTEGER NOT NULL)")
   db.execSQL("CREATE UNIQUE INDEX index_Reading_device_deviceSeconds_systolic_diastolic_pulse ON Reading (device,deviceSeconds,systolic,diastolic,pulse)")
   db.execSQL("CREATE TABLE SyncSession (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, at INTEGER NOT NULL, added INTEGER NOT NULL, outcome TEXT NOT NULL)")
   db.execSQL("INSERT INTO Reading VALUES(1,'00:11:22:33:44:55',86400,1000,123,78,65,'1e7b004e00000080510100410000000000',0)")
   db.version=1
  }
  val db=Room.databaseBuilder(c,Database::class.java,name).addMigrations(MIGRATION_1_2).build()
  try {
   val row=db.records().all().single();assertEquals(123,row.systolic);assertEquals(65,row.pulse)
   assertTrue(db.records().deletedKeys().isEmpty())
  } finally { db.close();c.deleteDatabase(name) }
 }
}
