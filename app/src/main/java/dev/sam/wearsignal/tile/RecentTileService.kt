package dev.sam.wearsignal.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.messages.ConversationRow

class RecentTileService : TileService() {

  companion object {
    private const val RESOURCES_VERSION = "1"
    private const val MAX_CONVERSATIONS = 3
    private const val SIGNAL_BLUE = 0xFF2C6BED.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GRAY = 0xFFAAAAAA.toInt()
  }

  override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
    TilePollWorker.enqueue(applicationContext)

    val tile = TileBuilders.Tile.Builder()
      .setResourcesVersion(RESOURCES_VERSION)
      .setFreshnessIntervalMillis(15 * 60 * 1000L)
      .setTileTimeline(
        TimelineBuilders.Timeline.Builder()
          .addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
              .setLayout(
                LayoutElementBuilders.Layout.Builder()
                  .setRoot(layout())
                  .build()
              )
              .build()
          )
          .build()
      )
      .build()

    return CallbackToFutureAdapter.getFuture { completer ->
      completer.set(tile)
      "tileRequest"
    }
  }

  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
    return CallbackToFutureAdapter.getFuture { completer ->
      completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
      "tileResources"
    }
  }

  private fun layout(): LayoutElementBuilders.LayoutElement {
    val column = LayoutElementBuilders.Column.Builder()
      .setWidth(expand())
      .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

    if (!AppDeps.account.isLinked) {
      column.addContent(text("Signal", 16f, SIGNAL_BLUE, bold = true))
      column.addContent(spacer(6f))
      column.addContent(text("Not linked", 13f, GRAY))
    } else {
      val unread = AppDeps.messages.unreadCount()
      val conversations = AppDeps.messages.conversations(MAX_CONVERSATIONS)

      column.addContent(text(if (unread > 0) "Signal · $unread new" else "Signal", 16f, SIGNAL_BLUE, bold = true))
      column.addContent(spacer(8f))

      if (conversations.isEmpty()) {
        column.addContent(text("No conversations", 13f, GRAY))
      } else {
        val redact = AppDeps.account.lockscreenPrivacyEnabled
        conversations.forEach { row ->
          column.addContent(conversationLine(row, redact))
          column.addContent(spacer(4f))
        }
      }
    }

    return LayoutElementBuilders.Box.Builder()
      .setWidth(expand())
      .setHeight(expand())
      .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
      .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
      .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
          .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(20f)).build())
          .setClickable(
            ModifiersBuilders.Clickable.Builder()
              .setId("open")
              .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                  .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                      .setPackageName(packageName)
                      .setClassName("dev.sam.wearsignal.ui.MainActivity")
                      .build()
                  )
                  .build()
              )
              .build()
          )
          .build()
      )
      .addContent(column.build())
      .build()
  }

  private fun conversationLine(row: ConversationRow, redact: Boolean): LayoutElementBuilders.LayoutElement {
    if (redact) {
      return text("${row.title} · New message", 13f, WHITE)
    }
    val sender = if (row.isGroup && !row.lastFromSelf) "${row.lastSender}: " else if (row.lastFromSelf) "Me: " else ""
    return text("${row.title} · $sender${row.lastBody}", 13f, WHITE)
  }

  private fun text(value: String, size: Float, color: Int, bold: Boolean = false): LayoutElementBuilders.LayoutElement {
    val fontStyle = LayoutElementBuilders.FontStyle.Builder()
      .setSize(sp(size))
      .setColor(argb(color))
    if (bold) {
      fontStyle.setWeight(FONT_WEIGHT_BOLD)
    }
    return LayoutElementBuilders.Text.Builder()
      .setText(value)
      .setMaxLines(1)
      .setOverflow(TEXT_OVERFLOW_ELLIPSIZE_END)
      .setFontStyle(fontStyle.build())
      .build()
  }

  private fun spacer(height: Float): LayoutElementBuilders.LayoutElement =
    LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()
}
