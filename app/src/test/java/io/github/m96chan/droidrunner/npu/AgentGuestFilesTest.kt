package io.github.m96chan.droidrunner.npu

import io.github.m96chan.droidrunner.runtime.GuestFs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The attacks a job can make on the agent through the paths it sends: each
 * plants the link a job could, and checks that nothing outside the guest
 * changed. The QNN stub stands in for a runner, so the files it reads and
 * writes are the ones the agent hands a real one.
 */
class AgentGuestFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var home: File
    private lateinit var server: DeviceAgentServer

    /** What the stub runner did, set per test. */
    private var runner: (File, List<File>, TensorIo.Target?) -> String = { _, _, _ -> """{"ok":true}""" }
    private var ran = false

    @Before fun startServer() {
        runtimeDir = temp.newFolder("runtime")
        home = File(runtimeDir, "home/runner").apply { mkdirs() }
        server = DeviceAgentServer(
            runtimeDir,
            requestedPort = 0,
            qnnModel = { model, _, _, inputs, target, _ ->
                ran = true
                runner(model, inputs, target)
            },
        ) { """{"stub":true}""" }
        server.start()
    }

    @After fun stopServer() {
        server.stop()
    }

    private fun link(at: File, target: File) {
        at.parentFile?.mkdirs()
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    private fun post(body: String, endpoint: String = "/v1/tests/model"): Pair<Int, JSONObject> {
        server.onJobActive(true)
        val token = File(home, DeviceAgentServer.TOKEN_FILE_NAME).readText()
        val connection = (URL("${server.url}$endpoint").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        return status to JSONObject(stream.bufferedReader().readText())
    }

    private fun model() = File(home, "model.tflite").apply { writeText("model") }

    @Test fun aSymlinkedModelIsRefusedAndNamed() {
        val secret = temp.newFile("secret").apply { writeText("app data") }
        link(File(home, "model.tflite"), secret)
        val (status, answer) = post("""{"path":"/home/runner/model.tflite","device":"qnn-htp"}""")
        assertEquals(400, status)
        assertEquals("invalid-request", answer.getString("code"))
        assertTrue(answer.getString("error"), answer.getString("error").contains("/home/runner/model.tflite is a symlink"))
        assertFalse(ran)
    }

    @Test fun theRunnerReadsAPrivateCopyThatTheJobCannotChange() {
        model()
        File(home, "in.bin").writeText("input")
        var seen = ""
        runner = { model, inputs, _ ->
            // The job rewrites its files while the model runs.
            File(home, "model.tflite").writeText("changed")
            File(home, "in.bin").writeText("changed")
            assertFalse(model.path.startsWith(home.path))
            seen = model.readText() + "/" + inputs.single().readText()
            """{"ok":true}"""
        }
        val (status, _) = post(
            """{"path":"/home/runner/model.tflite","device":"qnn-htp","inputs":["/home/runner/in.bin"]}""",
        )
        assertEquals(200, status)
        assertEquals("model/input", seen)
    }

    @Test fun anOutputPublishedOverAPlantedSymlinkLeavesItsTargetAlone() {
        model()
        val victim = temp.newFile("victim").apply { writeText("app data") }
        link(File(home, "out/output-0.bin"), victim)
        runner = { _, _, target ->
            File(target!!.directory.apply { mkdirs() }, "output-0.bin").writeText("tensor")
            """{"ok":true,"outputs":[{"path":"${target.asJobSeesIt}/output-0.bin"}]}"""
        }
        val (status, answer) = post(
            """{"path":"/home/runner/model.tflite","device":"qnn-htp","outputDir":"/home/runner/out"}""",
        )
        assertEquals(200, status)
        assertTrue(answer.toString(), answer.getBoolean("ok"))
        assertEquals("app data", victim.readText())
        val published = File(home, "out/output-0.bin")
        assertFalse(Files.isSymbolicLink(published.toPath()))
        assertEquals("tensor", published.readText())
    }

    @Test fun aBatchRowPublishesItsOutputsTheSameWay() {
        model()
        val victim = temp.newFile("victim").apply { writeText("app data") }
        link(File(home, "out/output-0.bin"), victim)
        runner = { _, _, target ->
            File(target!!.directory.apply { mkdirs() }, "output-0.bin").writeText("tensor")
            """{"ok":true}"""
        }
        val (status, answer) = post(
            """{"models":[{"id":"a","path":"/home/runner/model.tflite","device":"qnn-htp",""" +
                """"outputDir":"/home/runner/out"}]}""",
            endpoint = "/v1/tests/models",
        )
        assertEquals(200, status)
        assertTrue(answer.toString(), answer.getJSONArray("results").getJSONObject(0).getBoolean("ok"))
        assertEquals("app data", victim.readText())
        assertEquals("tensor", File(home, "out/output-0.bin").readText())
    }

    @Test fun aSymlinkedOutputDirectoryIsRefusedBeforeAnythingRuns() {
        model()
        val outside = temp.newFolder("outside")
        link(File(home, "out"), outside)
        val (status, answer) = post(
            """{"path":"/home/runner/model.tflite","device":"qnn-htp","outputDir":"/home/runner/out"}""",
        )
        assertEquals(400, status)
        assertTrue(answer.getString("error"), answer.getString("error").contains("/home/runner/out is a symlink"))
        assertFalse(ran)
        assertEquals(0, outside.list()!!.size)
    }

    @Test fun nothingStagedOutlivesTheRequest() {
        model()
        post("""{"path":"/home/runner/model.tflite","device":"qnn-htp"}""")
        assertEquals(0, GuestFs.stagingRoot(runtimeDir).list()?.size ?: 0)
    }

    @Test fun theTokenReplacesAPlantedSymlinkAndOnlyItsOwnerCanReadIt() {
        val victim = temp.newFile("victim").apply { writeText("app data") }
        val tokenFile = File(home, DeviceAgentServer.TOKEN_FILE_NAME)
        link(tokenFile, victim)
        server.onJobActive(true)
        assertEquals("app data", victim.readText())
        assertFalse(Files.isSymbolicLink(tokenFile.toPath()))
        assertEquals(48, tokenFile.readText().length)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile.toPath())))
    }

    @Test fun revokingTheTokenDeletesAPlantedSymlinkAndNotItsTarget() {
        val victim = temp.newFile("victim").apply { writeText("app data") }
        server.onJobActive(true)
        val tokenFile = File(home, DeviceAgentServer.TOKEN_FILE_NAME)
        tokenFile.delete()
        link(tokenFile, victim)
        server.onJobActive(false)
        assertFalse(Files.exists(tokenFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("app data", victim.readText())
    }
}
