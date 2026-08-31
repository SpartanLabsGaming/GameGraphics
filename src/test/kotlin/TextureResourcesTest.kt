import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Guards the contract [com.spartanlabs.graphics.TextureCache] relies on: every
 * texture is a classpath resource under `/textures/` (packaged from
 * `src/main/resources/textures/`). If the folder is renamed or a file is
 * moved out of it, this fails instead of only showing up as blank quads at
 * runtime.
 */
class TextureResourcesTest {

    @Test
    fun `the bundled textures resolve under the textures classpath folder`() {
        listOf("checkerboard.png", "natures prophet.jpg").forEach { name ->
            assertNotNull(
                javaClass.getResourceAsStream("/textures/$name"),
                "expected /textures/$name on the classpath"
            )
        }
    }
}
