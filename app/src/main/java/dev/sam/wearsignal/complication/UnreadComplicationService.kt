package dev.sam.wearsignal.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.R
import dev.sam.wearsignal.ui.MainActivity

/**
 * SHORT_TEXT complication showing the number of unread incoming messages across all
 * conversations. Push-only (UPDATE_PERIOD_SECONDS = 0): Glanceables.requestUpdate
 * invalidates it after every poll and when a thread is viewed on the watch.
 */
class UnreadComplicationService : SuspendingComplicationDataSourceService() {

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    if (type == ComplicationType.SHORT_TEXT) buildData(unread = 3) else null

  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    if (request.complicationType != ComplicationType.SHORT_TEXT) return null
    val unread = if (AppDeps.account.isLinked) AppDeps.messages.unreadCount() else 0
    return buildData(unread)
  }

  private fun buildData(unread: Int): ShortTextComplicationData {
    val tapIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val text = if (unread > 99) "99+" else unread.toString()
    val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_message)).build()

    return ShortTextComplicationData.Builder(
      text = PlainComplicationText.Builder(text).build(),
      contentDescription = PlainComplicationText.Builder("$unread unread messages").build()
    )
      .setMonochromaticImage(icon)
      .setTapAction(tapIntent)
      .build()
  }
}
