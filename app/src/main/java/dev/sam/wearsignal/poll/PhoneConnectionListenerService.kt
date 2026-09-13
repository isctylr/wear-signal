package dev.sam.wearsignal.poll

import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log

/**
 * Event-driven listener for phone connection changes.
 *
 * When app notifications (backgroundPollingEnabled) are enabled, background polling
 * is only armed while the phone is disconnected. When the phone reconnects, all polling
 * alarms are cancelled so the watch CPU experiences zero wakeups while near the phone.
 */
class PhoneConnectionListenerService : WearableListenerService() {

  companion object {
    private val TAG = Log.tag(PhoneConnectionListenerService::class)
  }

  override fun onPeerConnected(peer: Node) {
    Log.i(TAG, "Phone connected: ${peer.displayName} (${peer.id})")
    if (AppDeps.account.phoneConnectedOverride != null) return
    PollScheduler.onPhoneConnected(this)
  }

  override fun onPeerDisconnected(peer: Node) {
    Log.i(TAG, "Phone disconnected: ${peer.displayName} (${peer.id})")
    if (AppDeps.account.phoneConnectedOverride != null) return
    PollScheduler.onPhoneDisconnected(this)
  }

  override fun onConnectedNodes(connectedNodes: MutableList<Node>) {
    Log.i(TAG, "Connected nodes updated: count=${connectedNodes.size}")
    if (AppDeps.account.phoneConnectedOverride != null) return
    if (connectedNodes.isEmpty()) {
      PollScheduler.onPhoneDisconnected(this)
    } else {
      PollScheduler.onPhoneConnected(this)
    }
  }
}
