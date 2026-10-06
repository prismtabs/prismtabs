package io.prismtabs.obfuscation

import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * The obfuscated plugin jar the `obfuscationTest` task runs this suite against, in place of the
 * plugin's own classes.
 */
internal object ObfuscatedJar {

    val path: Path = Path.of(
        checkNotNull(System.getProperty("prismtabs.obfuscatedJar")) {
            "prismtabs.obfuscatedJar is not set; run this suite with ./gradlew obfuscationTest"
        },
    )

    fun <T> read(block: (JarFile) -> T): T = JarFile(path.toFile()).use(block)

    /**
     * Fails unless [type] came from the obfuscated jar. A class loaded from anywhere else, such as
     * an unobfuscated build output on the classpath, would make a passing test meaningless.
     */
    fun assertLoadedFromIt(type: Class<*>) {
        val location = type.protectionDomain.codeSource?.location?.toURI()?.let(Path::of)
        assertEquals(path, location, "${type.name} was not loaded from the obfuscated jar")
    }
}
