package dev.sam.wearsignal.tile

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.sam.wearsignal.complication.UnreadComplicationService

/** Pushes refreshed data to our active tiles and complications. */
object Glanceables {

  fun requestUpdate(context: Context) {
    try {
      TileService.getUpdater(context).requestUpdate(RecentTileService::class.java)
      ComplicationDataSourceUpdateRequester
        .create(context, ComponentName(context, UnreadComplicationService::class.java))
        .requestUpdateAll()
    } catch (t: Throwable) {
      // Ignored: services may not be active / permission may be missing.
    }
  }
}
