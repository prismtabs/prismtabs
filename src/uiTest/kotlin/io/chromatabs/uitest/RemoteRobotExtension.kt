package io.chromatabs.uitest

import com.intellij.remoterobot.RemoteRobot
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.io.File

/**
 * Resolves a [RemoteRobot] test parameter pointed at `runIdeForUiTests` (see README "UI testing")
 * and, on failure, saves a screenshot to `build/reports` — the only debugging aid available once
 * the IDE under test has already moved on.
 */
class RemoteRobotExtension : AfterTestExecutionCallback, ParameterResolver {

    private val remoteRobot = RemoteRobot(System.getProperty("remote-robot-url") ?: "http://127.0.0.1:8082")

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext) =
        parameterContext.parameter.type == RemoteRobot::class.java

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any =
        remoteRobot

    override fun afterTestExecution(context: ExtensionContext) {
        if (context.executionException.isPresent) {
            val bytes = remoteRobot.callJs<ByteArray>(
                """
                importPackage(java.io)
                importPackage(javax.imageio)
                const screenShot = new java.awt.Robot().createScreenCapture(
                    new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize())
                )
                const baos = new ByteArrayOutputStream()
                ImageIO.write(screenShot, "png", baos)
                baos.toByteArray()
                """,
            )
            File("build/reports").apply { mkdirs() }
                .resolve("${context.requiredTestMethod.name}.png")
                .writeBytes(bytes)
        }
    }
}
