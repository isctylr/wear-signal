package dev.sam.wearsignal.db

import android.content.Context
import dev.sam.wearsignal.account.AccountStore
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.signal.core.util.logging.Log
import java.io.File

/**
 * Single encrypted SQLCipher database holding the Signal protocol stores (per account identity: "aci"/"pni"),
 * received messages, and the contact-name cache. Encrypted with AES-256 using an Android Keystore passphrase.
 */
class WatchDatabase private constructor(
  context: Context,
  passphrase: ByteArray,
  @Suppress("UNUSED_PARAMETER") dummy: Unit
) : SQLiteOpenHelper(
  context,
  DATABASE_NAME,
  passphrase,
  null,
  DATABASE_VERSION,
  0,
  null,
  DATABASE_HOOK,
  true
) {

  companion object {
    const val DATABASE_NAME = "wearsignal.db"
    const val DATABASE_VERSION = 8
    private val TAG = Log.tag(WatchDatabase::class)

    private val DATABASE_HOOK = object : SQLiteDatabaseHook {
      override fun preKey(connection: SQLiteConnection) {
        connection.executeRaw("PRAGMA cipher_default_kdf_iter = 1;", null, null)
        connection.executeRaw("PRAGMA cipher_default_kdf_cache = ON;", null, null)
      }

      override fun postKey(connection: SQLiteConnection) {
        connection.executeRaw("PRAGMA cipher_kdf_cache = ON;", null, null)
        connection.executeRaw("PRAGMA cache_size = 2000;", null, null)
      }
    }

    init {
      System.loadLibrary("sqlcipher")
    }

    operator fun invoke(context: Context, account: AccountStore): WatchDatabase {
      val passphrase = account.databasePassphrase
      migratePlaintextIfNeeded(context, passphrase)
      val keyBytes = "x'$passphrase'".toByteArray(Charsets.UTF_8)
      return WatchDatabase(context, keyBytes, Unit)
    }

    private fun migratePlaintextIfNeeded(context: Context, passphrase: String) {
      val dbFile = context.getDatabasePath(DATABASE_NAME)
      if (!dbFile.exists() || dbFile.length() < 16) return

      val header = ByteArray(16)
      try {
        dbFile.inputStream().use { it.read(header) }
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to read database header", t)
        return
      }

      val isPlaintext = header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
      if (!isPlaintext) {
        // Already encrypted with SQLCipher
        return
      }

      Log.i(TAG, "Plaintext SQLite database detected; migrating to raw-key SQLCipher AES-256...")
      val tempEncrypted = File(dbFile.parentFile, "wearsignal_encrypted.db")
      if (tempEncrypted.exists()) tempEncrypted.delete()

      try {
        val plainDb = SQLiteDatabase.openOrCreateDatabase(dbFile.path, "", null, null)
        try {
          val version = plainDb.version
          plainDb.rawExecSQL("ATTACH DATABASE '${tempEncrypted.path}' AS encrypted KEY \"x'$passphrase'\";")
          plainDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
          plainDb.rawExecSQL("PRAGMA encrypted.user_version = $version;")
          plainDb.rawExecSQL("DETACH DATABASE encrypted;")
        } finally {
          plainDb.close()
        }

        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()

        if (dbFile.delete() && tempEncrypted.renameTo(dbFile)) {
          Log.i(TAG, "Successfully migrated database to raw-key SQLCipher encryption")
        } else {
          Log.e(TAG, "Failed to replace plaintext database with encrypted database")
        }
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to migrate plaintext database to SQLCipher", t)
        if (tempEncrypted.exists()) tempEncrypted.delete()
      }
    }
  }

  override fun onCreate(db: SQLiteDatabase) {

    createDirectoryTable(db)
    createGroupsTable(db)
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS identities (
        account TEXT NOT NULL,
        address TEXT NOT NULL,
        identity_key BLOB NOT NULL,
        added_at INTEGER NOT NULL,
        PRIMARY KEY (account, address)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS sessions (
        account TEXT NOT NULL,
        address TEXT NOT NULL,
        device INTEGER NOT NULL,
        record BLOB NOT NULL,
        PRIMARY KEY (account, address, device)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS one_time_prekeys (
        account TEXT NOT NULL,
        key_id INTEGER NOT NULL,
        record BLOB NOT NULL,
        stale_at INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (account, key_id)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS signed_prekeys (
        account TEXT NOT NULL,
        key_id INTEGER NOT NULL,
        record BLOB NOT NULL,
        PRIMARY KEY (account, key_id)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS kyber_prekeys (
        account TEXT NOT NULL,
        key_id INTEGER NOT NULL,
        record BLOB NOT NULL,
        is_last_resort INTEGER NOT NULL DEFAULT 0,
        stale_at INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (account, key_id)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS used_kyber_tuples (
        account TEXT NOT NULL,
        kyber_key_id INTEGER NOT NULL,
        signed_key_id INTEGER NOT NULL,
        base_key BLOB NOT NULL,
        UNIQUE (account, kyber_key_id, signed_key_id, base_key)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS sender_keys (
        account TEXT NOT NULL,
        address TEXT NOT NULL,
        device INTEGER NOT NULL,
        distribution_id TEXT NOT NULL,
        record BLOB NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (account, address, device, distribution_id)
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS messages (
        _id INTEGER PRIMARY KEY AUTOINCREMENT,
        peer TEXT NOT NULL,
        sender_aci TEXT NOT NULL,
        group_id TEXT,
        body TEXT NOT NULL,
        sent_at INTEGER NOT NULL,
        server_at INTEGER NOT NULL,
        from_self INTEGER NOT NULL DEFAULT 0,
        delivered_at INTEGER NOT NULL DEFAULT 0,
        read_at INTEGER NOT NULL DEFAULT 0,
        attachment_type TEXT,
        attachment_pointer BLOB,
        attachment_path TEXT,
        expires_at INTEGER NOT NULL DEFAULT 0
      )
      """
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS contacts (
        aci TEXT PRIMARY KEY,
        profile_key BLOB,
        name TEXT,
        fetched_at INTEGER NOT NULL DEFAULT 0,
        avatar_fetched_at INTEGER NOT NULL DEFAULT 0
      )
      """
    )
    createIndexes(db)
  }

  override fun onOpen(db: SQLiteDatabase) {
    super.onOpen(db)
    createIndexes(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      createDirectoryTable(db)
    }
    if (oldVersion < 3) {
      createGroupsTable(db)
      // Conversation key: group id for groups, the other party's ACI for 1:1. Legacy rows
      // stored the recipient in sender_aci for watch-sent messages, so COALESCE covers both
      // directions; phone-synced self messages (sender_aci = own ACI) end up in a stale
      // self-keyed conversation that prunes out naturally.
      db.execSQL("ALTER TABLE messages ADD COLUMN peer TEXT NOT NULL DEFAULT ''")
      db.execSQL("UPDATE messages SET peer = COALESCE(group_id, sender_aci)")
    }
    if (oldVersion < 4) {
      // Avatar fetches are tracked separately from name/state fetches so contacts and
      // groups that are already "fresh" still get their photo backfilled once.
      db.execSQL("ALTER TABLE contacts ADD COLUMN avatar_fetched_at INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE groups ADD COLUMN avatar_fetched_at INTEGER NOT NULL DEFAULT 0")
    }
    if (oldVersion < 5) {
      // Delivery/read receipt status for our own sent messages (matched by sent_at).
      db.execSQL("ALTER TABLE messages ADD COLUMN delivered_at INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE messages ADD COLUMN read_at INTEGER NOT NULL DEFAULT 0")
    }
    if (oldVersion < 6) {
      // First attachment of a message: content type, the serialized AttachmentPointer
      // (kept until the download succeeds or expires), and the local downscaled file.
      db.execSQL("ALTER TABLE messages ADD COLUMN attachment_type TEXT")
      db.execSQL("ALTER TABLE messages ADD COLUMN attachment_pointer BLOB")
      db.execSQL("ALTER TABLE messages ADD COLUMN attachment_path TEXT")
    }
    if (oldVersion < 7) {
      db.execSQL("ALTER TABLE messages ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0")
    }
    if (oldVersion < 8) {
      createIndexes(db)
    }
  }

  private fun createIndexes(db: SQLiteDatabase) {
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_peer_sent_at ON messages(peer, sent_at ASC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_expires_at ON messages(expires_at)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_sent_at ON messages(sent_at)")
  }

  /** Completely clears all tables during device unlink / data wipe. */
  fun wipeAllData() {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("messages", null, null)
      db.delete("identities", null, null)
      db.delete("sessions", null, null)
      db.delete("one_time_prekeys", null, null)
      db.delete("signed_prekeys", null, null)
      db.delete("kyber_prekeys", null, null)
      db.delete("used_kyber_tuples", null, null)
      db.delete("sender_keys", null, null)
      db.delete("contacts", null, null)
      db.delete("groups", null, null)
      db.delete("directory", null, null)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /** GroupsV2 state cache: master key harvested from message contexts, title/members fetched from the group server. */
  private fun createGroupsTable(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS groups (
        group_id TEXT PRIMARY KEY,
        master_key BLOB NOT NULL,
        revision INTEGER NOT NULL DEFAULT 0,
        title TEXT,
        members TEXT,
        fetched_at INTEGER NOT NULL DEFAULT 0,
        avatar_fetched_at INTEGER NOT NULL DEFAULT 0
      )
      """
    )
  }

  /** Discovered Signal contacts (phone number → ACI), cached from a CDSI lookup of the watch's contacts. */
  private fun createDirectoryTable(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS directory (
        e164 TEXT PRIMARY KEY,
        aci TEXT NOT NULL,
        name TEXT
      )
      """
    )
  }
}
