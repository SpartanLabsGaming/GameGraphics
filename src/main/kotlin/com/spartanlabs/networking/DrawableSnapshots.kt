package com.spartanlabs.networking

import com.spartanlabs.gaming.gameobjects.ActorSnapshot
import com.spartanlabs.gaming.gameobjects.AliveSnapshot
import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot

/**
 * Reduces any [DrawableSnapshot] the server broadcasts to just its drawable
 * core - the [VisibleObjectSnapshot] every kind wraps.
 *
 * As of GameTools 1.6.0 a `STATE` broadcast is a polymorphic list: a plain
 * `VisibleObject` still arrives as a [VisibleObjectSnapshot], but an `Actor`
 * arrives as an [ActorSnapshot] (adding speed/destination) and an `Alive` as
 * an [AliveSnapshot] (adding health/faction/owner/damage). This client only
 * renders position, size, colour, texture, angle and sub-objects, so it
 * unwraps every entry - recursively, since [VisibleObjectSnapshot.subObjects]
 * is itself a list of [DrawableSnapshot] - back to the plain form the
 * renderer, picker and portrait already understand.
 */
internal fun DrawableSnapshot.drawableCore(): VisibleObjectSnapshot = when (this) {
    is VisibleObjectSnapshot ->
        if (subObjects.all { it is VisibleObjectSnapshot }) this
        else copy(subObjects = subObjects.map { it.drawableCore() })
    is ActorSnapshot -> visibleObject.drawableCore()
    is AliveSnapshot -> actor.drawableCore()
}
