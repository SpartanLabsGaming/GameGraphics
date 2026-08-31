import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Guards the contract [com.spartanlabs.audio.SoundPlayer] relies on: every
 * sound effect is a classpath resource under `/sounds/` (packaged from
 * `src/main/resources/sounds/`). If the folder is renamed or a file is moved
 * out of it, this fails instead of only showing up as a silent click at
 * runtime.
 */
class SoundResourcesTest {

    @Test
    fun `the bundled sounds resolve under the sounds classpath folder`() {
        listOf("beep-07a.mp3").forEach { name ->
            assertNotNull(
                javaClass.getResourceAsStream("/sounds/$name"),
                "expected /sounds/$name on the classpath"
            )
        }
    }
}
