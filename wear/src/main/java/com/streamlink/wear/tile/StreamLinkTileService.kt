package com.streamlink.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.*
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.streamlink.shared.GlobalStreamState
import com.streamlink.wear.engagement.PickupCountListenerService
import com.streamlink.wear.ui.WearMainActivity

class StreamLinkTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val isStreaming = GlobalStreamState.snapshot.value.state == GlobalStreamState.State.STREAMING
        val pickupsAvoided = PickupCountListenerService.readCachedCount(applicationContext)

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(buildLayout(isStreaming, pickupsAvoided))
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<androidx.wear.tiles.ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            androidx.wear.tiles.ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .build()
        )
    }

    private fun buildLayout(
        isStreaming: Boolean,
        pickupsAvoided: Int
    ): androidx.wear.protolayout.LayoutElementBuilders.Layout {
        val statusText  = if (isStreaming) "Streaming" else "Tap to Stream"
        val buttonColor = if (isStreaming) 0xFFE53935.toInt() else 0xFF4CAF50.toInt()

        val actionIntent = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(WearMainActivity::class.java.name)
                    .build()
            ).build()

        val content = Column.Builder()
            .addContent(
                Text.Builder()
                    .setText(
                        androidx.wear.protolayout.TypeBuilders.StringProp.Builder("StreamLink").build()
                    )
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(16f))
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(
                Box.Builder()
                    .setWidth(dp(80f))
                    .setHeight(dp(80f))
                    .setModifiers(
                        Modifiers.Builder()
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(buttonColor))
                                    .setCorner(Corner.Builder().setRadius(dp(40f)).build())
                                    .build()
                            )
                            .setClickable(
                                Clickable.Builder()
                                    .setOnClick(actionIntent)
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        Text.Builder()
                            .setText(
                                androidx.wear.protolayout.TypeBuilders.StringProp.Builder(
                                    if (isStreaming) "⏹" else "▶"
                                ).build()
                            )
                            .setFontStyle(FontStyle.Builder().setSize(sp(28f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText(
                        androidx.wear.protolayout.TypeBuilders.StringProp.Builder(statusText).build()
                    )
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(0xFFBDBDBD.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText(
                        androidx.wear.protolayout.TypeBuilders.StringProp.Builder(
                            if (pickupsAvoided > 0) "🖐 $pickupsAvoided today" else " "
                        ).build()
                    )
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(10f))
                            .setColor(argb(0xFF81C784.toInt()))
                            .build()
                    )
                    .build()
            )
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .build()

        return androidx.wear.protolayout.LayoutElementBuilders.Layout.Builder()
            .setRoot(content)
            .build()
    }
}
