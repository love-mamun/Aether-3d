package com.example.data.local

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

private const val TAG = "AetherDatabase"

@Database(
    entities = [
        CommandLogEntity::class,
        MemoryNoteEntity::class,
        VoiceRoutineEntity::class,
        QuickContactEntity::class,
        ReminderEntity::class,
        ScreenMacroEntity::class,
        AppPlaybookEntity::class,
        SmartTriggerRuleEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun commandLogDao(): CommandLogDao
    abstract fun memoryNoteDao(): MemoryNoteDao
    abstract fun voiceRoutineDao(): VoiceRoutineDao
    abstract fun quickContactDao(): QuickContactDao
    abstract fun reminderDao(): ReminderDao
    abstract fun screenMacroDao(): ScreenMacroDao
    abstract fun appPlaybookDao(): AppPlaybookDao
    abstract fun smartTriggerRuleDao(): SmartTriggerRuleDao

    companion object {
        private const val LEGACY_DB_NAME = "aether_3d_vault.db"
        private const val ENCRYPTED_DB_NAME = "aether_3d_vault_encrypted.db"

        @Volatile
        private var INSTANCE: AetherDatabase? = null

        @Volatile
        var isSqlCipherActive: Boolean = false
            private set

        @Volatile
        var encryptionBadgeLabel: String = "Local SQLite"
            private set

        fun getInstance(context: Context): AetherDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): AetherDatabase {
            val cryptoManager = VaultCryptoManager(appContext)
            val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

            val cipherFactory: SupportOpenHelperFactory? = if (!isRobolectric) {
                runCatching {
                    System.loadLibrary("sqlcipher")
                    val passphrase = cryptoManager.getOrCreateDatabasePassphrase()
                    SupportOpenHelperFactory(passphrase)
                }.onFailure { err ->
                    Log.w(TAG, "SQLCipher native library unavailable (${err.message}); using standard SQLite")
                }.getOrNull()
            } else {
                null
            }

            isSqlCipherActive = cipherFactory != null
            encryptionBadgeLabel = when {
                cipherFactory != null && cryptoManager.isHardwareKeystoreBacked ->
                    "SQLCipher AES-256 · Keystore"
                cipherFactory != null ->
                    "SQLCipher AES-256 Encrypted"
                else ->
                    "Local SQLite Vault"
            }

            val dbName = if (cipherFactory != null) ENCRYPTED_DB_NAME else LEGACY_DB_NAME
            val builder = Room.databaseBuilder(
                appContext,
                AetherDatabase::class.java,
                dbName
            )

            if (cipherFactory != null) {
                builder.openHelperFactory(cipherFactory)
            }

            val migration1To2 = object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS reminders (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            triggerAtMillis INTEGER NOT NULL,
                            repeatDaily INTEGER NOT NULL,
                            includeInBriefing INTEGER NOT NULL,
                            isCompleted INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS screen_macros (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            voiceTrigger TEXT NOT NULL,
                            stepsJson TEXT NOT NULL,
                            stepCount INTEGER NOT NULL,
                            runCount INTEGER NOT NULL,
                            lastRunAt INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }

            val migration2To3 = object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS app_playbooks (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            appPackage TEXT NOT NULL,
                            appLabel TEXT NOT NULL,
                            intentPattern TEXT NOT NULL,
                            variableKeysJson TEXT NOT NULL,
                            stepsJson TEXT NOT NULL,
                            successCount INTEGER NOT NULL,
                            failureCount INTEGER NOT NULL,
                            avgDurationMs INTEGER NOT NULL,
                            isPinned INTEGER NOT NULL,
                            lastUsedAt INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS smart_trigger_rules (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            triggerType TEXT NOT NULL,
                            conditionValue TEXT NOT NULL,
                            actionMode TEXT NOT NULL,
                            actionPayload TEXT NOT NULL,
                            cooldownMinutes INTEGER NOT NULL,
                            isEnabled INTEGER NOT NULL,
                            lastTriggeredAt INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }

            return builder
                .addMigrations(migration1To2, migration2To3)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            val instance = getInstance(appContext)
                            val migrated = if (cipherFactory != null) {
                                migrateLegacyUnencryptedIfPresent(appContext, instance)
                            } else {
                                false
                            }
                            if (!migrated) {
                                seedDefaultRoutines(instance.voiceRoutineDao())
                            }
                            seedDefaultTriggerRules(instance.smartTriggerRuleDao())
                        }
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            val instance = getInstance(appContext)
                            seedDefaultTriggerRules(instance.smartTriggerRuleDao())
                        }
                    }
                })
                .build()
        }

        private suspend fun seedDefaultRoutines(routineDao: VoiceRoutineDao) {
            if (routineDao.getAllRoutinesSnapshot().isNotEmpty()) return
            routineDao.insertRoutine(
                VoiceRoutineEntity(
                    triggerPhrase = "Morning Briefing",
                    description = "Weather check, battery telemetry & 7:30 AM reminder",
                    instructionPrompt = "Check local weather, report my battery status, and summarize my pinned memories."
                )
            )
            routineDao.insertRoutine(
                VoiceRoutineEntity(
                    triggerPhrase = "Study Focus",
                    description = "Set 25-minute Pomodoro timer & lower media volume",
                    instructionPrompt = "Start a 25 minute timer labeled Deep Focus and set media volume to 25 percent."
                )
            )
            routineDao.insertRoutine(
                VoiceRoutineEntity(
                    triggerPhrase = "Night Shield",
                    description = "Dim brightness, mute media & set 7 AM alarm",
                    instructionPrompt = "Turn off flashlight, set brightness to 20 percent, and set an alarm for 7:00 AM."
                )
            )
        }

        private suspend fun seedDefaultTriggerRules(triggerDao: SmartTriggerRuleDao) {
            if (triggerDao.getAllRulesSnapshot().isNotEmpty()) return
            triggerDao.insertRule(
                SmartTriggerRuleEntity(
                    name = "Headset Unread Digest",
                    triggerType = "BLUETOOTH_CONNECTED",
                    conditionValue = "",
                    actionMode = "SPEAK_NOTIFICATION_DIGEST",
                    actionPayload = "Read unread notification digest on Bluetooth audio connect",
                    cooldownMinutes = 10,
                    isEnabled = false
                )
            )
            triggerDao.insertRule(
                SmartTriggerRuleEntity(
                    name = "Low Battery Shield",
                    triggerType = "BATTERY_LOW",
                    conditionValue = "20",
                    actionMode = "RUN_PROMPT_COMMAND",
                    actionPayload = "Set brightness to 25 percent and summarize battery status.",
                    cooldownMinutes = 45,
                    isEnabled = false
                )
            )
        }

        private suspend fun migrateLegacyUnencryptedIfPresent(
            appContext: Context,
            targetEncryptedDb: AetherDatabase
        ): Boolean {
            val legacyFile = appContext.getDatabasePath(LEGACY_DB_NAME)
            if (!legacyFile.exists() || legacyFile.length() == 0L) return false
            return runCatching {
                val legacyDb = Room.databaseBuilder(
                    appContext,
                    AetherDatabase::class.java,
                    LEGACY_DB_NAME
                ).build()
                try {
                    val memories = legacyDb.memoryNoteDao().getTopMemoriesSnapshot()
                    val routines = legacyDb.voiceRoutineDao().getAllRoutinesSnapshot()
                    val contacts = legacyDb.quickContactDao().getAllContactsSnapshot()

                    memories.forEach { targetEncryptedDb.memoryNoteDao().insertMemory(it.copy(id = 0)) }
                    routines.forEach { targetEncryptedDb.voiceRoutineDao().insertRoutine(it.copy(id = 0)) }
                    contacts.forEach { targetEncryptedDb.quickContactDao().insertContact(it.copy(id = 0)) }
                } finally {
                    legacyDb.close()
                }
                appContext.deleteDatabase(LEGACY_DB_NAME)
                Log.i(TAG, "Migrated legacy vault records into SQLCipher encrypted database")
                true
            }.getOrDefault(false)
        }
    }
}
