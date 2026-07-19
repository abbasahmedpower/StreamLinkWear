package com.streamlink.wear.engagement

import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.streamlink.wear.tile.StreamLinkTileService

/**
 * Receives the "/pickup_avoided_count" DataItem pushed from
 * RemoteControlAccessibilityService (phone side) and caches it locally so
 * StreamLinkTileService can render it without needing a live connection.
 */
class PickupCountListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH) continue

            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val count = map.getInt("count", 0)

            getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
                .putInt(KEY_COUNT, count)
                .apply()

            Log.d(TAG, "Cached pickup-avoided count from phone: $count")

            // Ask the tile system to re-render with the fresh number.
            TileService.getUpdater(applicationContext)
                .requestUpdate(StreamLinkTileService::class.java)
        }
    }

    companion object {
        private const val TAG = "PickupCountListener"
        private const val PATH = "/pickup_avoided_count"
        const val PREFS_FILE = "sl_wear_pickup_cache_v1"
        const val KEY_COUNT = "count"

        /** Read the last cached count for display — safe to call with no live connection. */
        fun readCachedCount(context: Context): Int =
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)
    }
}
