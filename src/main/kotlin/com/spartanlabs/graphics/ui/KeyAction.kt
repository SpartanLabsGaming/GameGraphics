package com.spartanlabs.graphics.ui

import com.spartanlabs.gaming.networking.MouseAction

/** The kind of keyboard event that occurred. Key repeats are not reported. */
enum class KeyActionType { PRESS, RELEASE }

/**
 * A single keyboard input event.
 *
 * The client has no wire DTO for key input (GameTools ships [MouseAction] but
 * no keyboard equivalent), so this is a purely client-side type: [Window]
 * builds one from every GLFW key callback and routes it into the current UI
 * [Scene] via [Scene.dispatchKey].
 *
 * @property type whether the key went down or came back up
 * @property key the GLFW key code (e.g. `GLFW_KEY_SPACE`), matching the codes a
 * [Button] is assigned
 */
data class KeyAction(
    val type: KeyActionType,
    val key: Int
)
