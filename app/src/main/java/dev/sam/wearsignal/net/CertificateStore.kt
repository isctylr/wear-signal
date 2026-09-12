package dev.sam.wearsignal.net

import org.signal.core.util.logging.Log
import org.signal.libsignal.metadata.certificate.SenderCertificate

/**
 * In-memory cache for the local device's delivery certificate.
 * Refreshes automatically when within 30 minutes of expiration or when missing.
 */
object CertificateStore {

  private val TAG = Log.tag(CertificateStore::class)
  private const val REFRESH_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes

  @Volatile
  private var cachedCertificate: SenderCertificate? = null

  @Synchronized
  fun getCertificate(net: SignalNet): SenderCertificate? {
    val current = cachedCertificate
    val now = System.currentTimeMillis()
    if (current != null) {
      val exp = current.expiration
      val expMs = if (exp > 10_000_000_000L) exp else exp * 1000L
      if (expMs - now > REFRESH_THRESHOLD_MS) {
        return current
      }
    }

    return try {
      val fresh = net.certificateApi.getDeliveryCertificate()
      cachedCertificate = fresh
      val exp = fresh.expiration
      val expMs = if (exp > 10_000_000_000L) exp else exp * 1000L
      Log.i(TAG, "Fetched fresh delivery certificate (expires in ${(expMs - now) / 60000}m)")
      fresh
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to fetch delivery certificate", t)
      // If we have an existing certificate that hasn't fully expired yet, use it as fallback
      if (current != null) {
        val exp = current.expiration
        val expMs = if (exp > 10_000_000_000L) exp else exp * 1000L
        if (expMs > now) current else null
      } else {
        null
      }
    }
  }

  @Synchronized
  fun clear() {
    cachedCertificate = null
    Log.i(TAG, "Cleared cached delivery certificate")
  }
}
