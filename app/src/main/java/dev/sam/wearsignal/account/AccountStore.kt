package dev.sam.wearsignal.account

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.zkgroup.profiles.ProfileKey

/**
 * Persistent storage for the linked account: identity, credentials, and app settings.
 * Sensitive cryptographic keys and passwords are encrypted at rest using Android Keystore.
 */
class AccountStore(context: Context) {

  companion object {
    private val TAG = Log.tag(AccountStore::class)
    private val SENSITIVE_KEYS = listOf("password", "aci_identity", "pni_identity", "profile_key", "db_passphrase")
  }


  private val prefs: SharedPreferences = context.getSharedPreferences("account", Context.MODE_PRIVATE)

  private val securePrefs: SharedPreferences by lazy {
    try {
      val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
      EncryptedSharedPreferences.create(
        context,
        "secure_account",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
      )
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to standard prefs", t)
      prefs
    }
  }

  init {
    migrateCredentialsIfNecessary()
  }

  private fun migrateCredentialsIfNecessary() {
    val toMigrate = mutableMapOf<String, String>()
    for (key in SENSITIVE_KEYS) {
      val value = prefs.getString(key, null)
      if (value != null) {
        toMigrate[key] = value
      }
    }
    if (toMigrate.isNotEmpty() && securePrefs !== prefs) {
      securePrefs.edit {
        for ((k, v) in toMigrate) {
          putString(k, v)
        }
      }
      prefs.edit {
        for (k in toMigrate.keys) {
          remove(k)
        }
      }
      Log.i(TAG, "Migrated ${toMigrate.size} credentials to EncryptedSharedPreferences")
    }
  }

  val isLinked: Boolean
    get() = prefs.getString("aci", null) != null && deviceId > 0

  var aci: ACI?
    get() = prefs.getString("aci", null)?.let { ACI.parseOrNull(it) }
    set(value) = prefs.edit { putString("aci", value?.toString()) }

  var pni: PNI?
    get() = prefs.getString("pni", null)?.let { PNI.parseOrNull(it) }
    set(value) = prefs.edit { putString("pni", value?.toString()) }

  var e164: String?
    get() = prefs.getString("e164", null)
    set(value) = prefs.edit { putString("e164", value) }

  var deviceId: Int
    get() = prefs.getInt("device_id", -1)
    set(value) = prefs.edit { putInt("device_id", value) }

  var password: String?
    get() = securePrefs.getString("password", null)
    set(value) = securePrefs.edit { putString("password", value) }

  var aciIdentityKeyPair: IdentityKeyPair?
    get() = securePrefs.getString("aci_identity", null)?.let { IdentityKeyPair(Base64.decode(it)) }
    set(value) = securePrefs.edit { putString("aci_identity", value?.let { Base64.encodeWithPadding(it.serialize()) }) }

  var pniIdentityKeyPair: IdentityKeyPair?
    get() = securePrefs.getString("pni_identity", null)?.let { IdentityKeyPair(Base64.decode(it)) }
    set(value) = securePrefs.edit { putString("pni_identity", value?.let { Base64.encodeWithPadding(it.serialize()) }) }

  var profileKey: ProfileKey?
    get() = securePrefs.getString("profile_key", null)?.let { ProfileKey(Base64.decode(it)) }
    set(value) = securePrefs.edit { putString("profile_key", value?.let { Base64.encodeWithPadding(it.serialize()) }) }

  val databasePassphrase: String
    get() {
      val existing = securePrefs.getString("db_passphrase", null)
      if (existing != null) {
        return existing
      }
      val randomBytes = ByteArray(32)
      java.security.SecureRandom().nextBytes(randomBytes)
      val hex = randomBytes.joinToString("") { "%02x".format(it) }
      securePrefs.edit { putString("db_passphrase", hex) }
      return hex
    }


  var aciRegistrationId: Int
    get() = prefs.getInt("aci_registration_id", 0)
    set(value) = prefs.edit { putInt("aci_registration_id", value) }

  var pniRegistrationId: Int
    get() = prefs.getInt("pni_registration_id", 0)
    set(value) = prefs.edit { putInt("pni_registration_id", value) }

  var lastPollAt: Long
    get() = prefs.getLong("last_poll_at", 0L)
    set(value) = prefs.edit { putLong("last_poll_at", value) }

  var lastSilentDrainAt: Long
    get() = prefs.getLong("last_silent_drain_at", 0L)
    set(value) = prefs.edit { putLong("last_silent_drain_at", value) }

  var lastPreKeyCheckAt: Long
    get() = prefs.getLong("last_prekey_check_at", 0L)
    set(value) = prefs.edit { putLong("last_prekey_check_at", value) }

  var pollIntervalMinutes: Int
    get() = prefs.getInt("poll_interval_minutes", 5)
    set(value) = prefs.edit { putInt("poll_interval_minutes", value) }

  /**
   * Whether to keep polling in the background on a recurring alarm. Off by default: in normal use
   * the phone bridges Signal's notifications to the watch, so background polling is pure battery
   * cost. Flip on (e.g. when the phone is dead) to use the watch as a standalone fallback.
   */
  var backgroundPollingEnabled: Boolean
    get() = prefs.getBoolean("background_polling_enabled", false)
    set(value) = prefs.edit { putBoolean("background_polling_enabled", value) }

  /** Debug override: pretend the phone is connected/disconnected. null = use real NodeClient state. */
  var phoneConnectedOverride: Boolean?
    get() = if (prefs.contains("phone_connected_override")) prefs.getBoolean("phone_connected_override", false) else null
    set(value) = prefs.edit { if (value == null) remove("phone_connected_override") else putBoolean("phone_connected_override", value) }

  /** Whether to redact sender name and message content on the lock screen / off-wrist. */
  var lockscreenPrivacyEnabled: Boolean
    get() = prefs.getBoolean("lockscreen_privacy_enabled", true)
    set(value) = prefs.edit { putBoolean("lockscreen_privacy_enabled", value) }

  /**
   * Whether viewing a thread sends READ receipts to the message authors. Off by default: the
   * watch can't see the account's read-receipts privacy setting, so it stays silent to
   * senders until the user opts in. Reads always sync to our own devices regardless.
   */
  var sendReadReceipts: Boolean
    get() = prefs.getBoolean("send_read_receipts", false)
    set(value) = prefs.edit { putBoolean("send_read_receipts", value) }

  fun clear() {
    prefs.edit { clear() }
    if (securePrefs !== prefs) {
      securePrefs.edit { clear() }
    }
  }

}
