package com.spartanlabs.graphics.ui

import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.geometry.Square

/**
 * An [Element] that mirrors the look of a visible object - a live "portrait"
 * of whatever the player currently has selected. [subject] is polled every
 * frame, so the portrait follows the selection as it changes and renders
 * nothing while [subject] returns null.
 *
 * It copies the subject's own [color][VisibleObjectSnapshot.color] and
 * [texture][VisibleObjectSnapshot.texture] name straight from the snapshot;
 * the renderer resolves the name and multiplies the two, so the portrait is
 * the object's texture in the object's colour.
 */
class Portrait(
    private val subject: () -> VisibleObjectSnapshot?,
    override val position: Square
) : Element(position) {

    override val color: Color
        get() = subject()?.color?.let { Color(it.r, it.g, it.b, it.a) } ?: Color.TRANSPARENT

    override val texture: String?
        get() = subject()?.texture
}
