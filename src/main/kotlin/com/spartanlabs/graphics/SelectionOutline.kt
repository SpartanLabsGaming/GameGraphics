package com.spartanlabs.graphics

/** One edge of a selection outline, as a world-pixel rectangle centred at ([cx], [cy]). */
internal data class OutlineEdge(
    val cx: Double,
    val cy: Double,
    val widthPx: Double,
    val heightPx: Double,
)

/**
 * Pure geometry for the in-world selection highlight: the four thin rectangles
 * that make up an axis-aligned outline around a selected unit. No GL, no
 * state - [Window.drawSelectionOutline] feeds each rectangle through
 * [com.spartanlabs.networking.NdcConverter] exactly as it does an actor quad,
 * so the outline tracks the unit through camera pan and zoom.
 */
internal object SelectionOutline {

    /**
     * The four edge rectangles of an outline around a unit centred at
     * ([centerX], [centerY]) and sized [unitWidth] x [unitHeight] (all world
     * pixels, Y increasing downward as actor locations are). The outline's
     * inner edge sits [marginPx] outside the unit box on every side, and each
     * edge is [thicknessPx] thick; the edges tile the frame with no overlap
     * and no gap - top and bottom span the full outer width, left and right
     * fill the height between them.
     *
     * Order: top, bottom, left, right.
     */
    fun edges(
        centerX: Double,
        centerY: Double,
        unitWidth: Double,
        unitHeight: Double,
        marginPx: Double,
        thicknessPx: Double,
    ): List<OutlineEdge> {
        val innerHalfWidth = unitWidth / 2.0 + marginPx
        val innerHalfHeight = unitHeight / 2.0 + marginPx
        val outerWidth = 2.0 * (innerHalfWidth + thicknessPx)
        val innerHeight = 2.0 * innerHalfHeight

        return listOf(
            OutlineEdge(centerX, centerY - innerHalfHeight - thicknessPx / 2.0, outerWidth, thicknessPx),
            OutlineEdge(centerX, centerY + innerHalfHeight + thicknessPx / 2.0, outerWidth, thicknessPx),
            OutlineEdge(centerX - innerHalfWidth - thicknessPx / 2.0, centerY, thicknessPx, innerHeight),
            OutlineEdge(centerX + innerHalfWidth + thicknessPx / 2.0, centerY, thicknessPx, innerHeight),
        )
    }
}
