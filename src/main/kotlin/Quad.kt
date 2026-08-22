import kotlinx.serialization.Serializable

/** A quad's networked state, as reported by the server. Position and size are in pixels. */
@Serializable
data class Quad(
    val id: Int,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

/** The JSON envelope the server sends over UDP: a batch of quad positions. */
@Serializable
data class QuadUpdatePacket(val quads: List<Quad>)