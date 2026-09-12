package dev.sam.wearsignal.net

import com.fasterxml.jackson.annotation.JsonProperty
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.get
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import java.io.IOException

/**
 * Signal Server delivery certificate endpoint for Sealed Sender (Unidentified Delivery).
 * GET /v1/certificate/delivery returns an authenticated sender certificate valid for ~24 hours.
 */
class CertificateApi(private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket) {

  data class DeliveryCertificateResponse(
    @param:JsonProperty("certificate") val certificate: ByteArray
  )

  @Throws(IOException::class)
  fun getDeliveryCertificate(): SenderCertificate {
    val request = WebSocketRequestMessage.get("/v1/certificate/delivery")
    val response = NetworkResult.fromWebSocketRequest(
      authWebSocket,
      request,
      DeliveryCertificateResponse::class
    ).successOrThrow()

    return SenderCertificate(response.certificate)
  }
}
