package com.xiddoc.claudeophobia.widget

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared bitmap lifecycle for the bitmap-rendered widgets (graph + circle).
 *
 * RemoteViews.setImageViewBitmap parcels the bitmap to the launcher process
 * asynchronously, so recycling our copy *synchronously* after updateAppWidget can
 * race that parcel and blank or crash the widget. Instead we keep the previous
 * bitmap per appWidgetId and recycle it at the START of that id's next render — by
 * then the launcher has long since received its own copy. Memory stays bounded to
 * at most one retained bitmap per placed widget.
 *
 * The map is keyed by a (provider-tag, appWidgetId) pair so the graph and circle
 * widgets never clobber each other's entry for the same id.
 */
object WidgetBitmaps {

    private val previous = ConcurrentHashMap<String, Bitmap>()

    private fun key(tag: String, id: Int) = "$tag#$id"

    /** Recycle the bitmap retained for [tag]/[id] from the prior render. Call first. */
    fun recyclePrevious(tag: String, id: Int) {
        previous.remove(key(tag, id))?.let { if (!it.isRecycled) it.recycle() }
    }

    /** Retain [bmp] as the current bitmap for [tag]/[id]. Call after updateAppWidget. */
    fun remember(tag: String, id: Int, bmp: Bitmap) {
        previous[key(tag, id)] = bmp
    }

    /** Recycle and drop the entry when a widget instance is deleted. */
    fun release(tag: String, id: Int) {
        previous.remove(key(tag, id))?.let { if (!it.isRecycled) it.recycle() }
    }

    /**
     * Converts a max-dimension in dp (from AppWidgetManager options) to a bounded
     * pixel size. When the launcher hasn't reported a size yet ([maxDp] <= 0 on the
     * very first onUpdate) we fall back to [fallbackDp] so the first bitmap is never
     * blank or 1px, then clamp into [[minPx], [maxPx]] to cap memory.
     */
    fun boundDimPx(maxDp: Int, density: Float, minPx: Int, maxPx: Int, fallbackDp: Int): Int {
        val dp = if (maxDp > 0) maxDp else fallbackDp
        val px = (dp * density).toInt()
        return px.coerceIn(minPx, maxPx)
    }
}
