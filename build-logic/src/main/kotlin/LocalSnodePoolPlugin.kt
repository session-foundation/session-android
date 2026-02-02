import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HasUnitTest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

class LocalSnodePoolPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponents.onVariants { variant ->
                val shouldRun = variant.buildType !in setOf("debug")
                if (!shouldRun) return@onVariants

                val task = project.tasks.register(
                    "generate${variant.name.capitalized()}LocalSnodePool",
                    GenerateLocalSnodePoolTask::class.java
                ) {
                    // build/generated/<variant>/snodes/snode_pool.json
                    outputDir.set(project.layout.buildDirectory.dir("generated/${variant.name}"))
                    seedUrls.set(
                        listOf(
                            "https://seed1.getsession.org:4443/json_rpc",
                            "https://seed2.getsession.org:4443/json_rpc",
                            "https://seed3.getsession.org:4443/json_rpc",
                        )
                    )
                }

                // Add generated assets directory
                variant.sources.assets?.addGeneratedSourceDirectory(
                    task,
                    GenerateLocalSnodePoolTask::outputDir
                )

                // Also add the generated dir to unit test resources so Gradle wires task deps correctly
                (variant as? HasUnitTest)?.unitTest?.sources?.resources?.addGeneratedSourceDirectory(
                    task,
                    GenerateLocalSnodePoolTask::outputDir
                )
            }
        }
    }
}

abstract class GenerateLocalSnodePoolTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val seedUrls: ListProperty<String>

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        val outDirFile = outputDir.get().asFile
        outDirFile.mkdirs()

        val outFile = outDirFile.resolve("snodes").resolve("snode_pool.json")
        outFile.parentFile.mkdirs()

        val requestBody = """
            {
              "method": "get_service_nodes",
              "params": {
                "active_only": true,
                "fields": {
                  "public_ip": true,
                  "storage_port": true,
                  "pubkey_ed25519": true,
                  "pubkey_x25519": true
                }
              }
            }
        """.trimIndent()

        val seeds = seedUrls.get().shuffled()
        var lastError: Throwable? = null
        var body: String? = null
        var usedSeed: String? = null

        for (seed in seeds) {
            try {
                val candidate = fetchWithCurl(seed, requestBody)

                if (candidate.isBlank()) {
                    throw IllegalStateException("Empty response from $seed")
                }

                val count = validateSnodePoolJson(candidate)

                body = candidate
                usedSeed = seed
                logger.lifecycle("Validated snode pool JSON ($count nodes) from $seed")
                break
            } catch (t: Throwable) {
                lastError = t
                logger.warn("Failed to fetch/validate snode pool from $seed: ${t.message}")
            }
        }

        if (body == null) {
            throw GradleException(
                "Failed to generate local snode pool JSON from all seeds",
                lastError
            )
        }

        val tmp = Files.createTempFile(outFile.parentFile.toPath(), "snode_pool", ".json.tmp")
        try {
            Files.writeString(tmp, body, Charsets.UTF_8)
            Files.move(tmp, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }

        logger.lifecycle("Wrote generated asset: ${outFile.absolutePath} (source=$usedSeed)")
    }

    private fun fetchWithCurl(seed: String, requestBody: String): String {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val result = execOps.exec {
            commandLine(
                "curl",
                "--fail",
                "--silent",
                "--show-error",
                "-k",
                "-X", "POST",
                "-H", "Content-Type: application/json",
                "--data", requestBody,
                seed
            )
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw IllegalStateException(
                "curl failed (exit=${result.exitValue}): ${stderr.toString("UTF-8").trim()}"
            )
        }

        return stdout.toString("UTF-8").trim()
    }

    /**
     * Validates JSON format and returns node count.
     *
     * Expected shape:
     * { "result": { "service_node_states": [ { public_ip, storage_port, pubkey_ed25519, pubkey_x25519 }, ... ] } }
     */
    private fun validateSnodePoolJson(json: String): Int {
        val root = groovy.json.JsonSlurper().parseText(json)

        val result = (root as? Map<*, *>)?.get("result") as? Map<*, *>
            ?: throw IllegalStateException("Missing top-level 'result' object")

        val states = result["service_node_states"] as? List<*>
            ?: throw IllegalStateException("Missing 'service_node_states' array")

        val parsed = states.mapNotNull { it as? Map<*, *> }.map { m ->
            val ip = m["public_ip"] as? String ?: throw IllegalStateException("Missing public_ip")
            val portNum = m["storage_port"] as? Number ?: throw IllegalStateException("Missing storage_port")
            val ed = m["pubkey_ed25519"] as? String ?: throw IllegalStateException("Missing pubkey_ed25519")
            val x = m["pubkey_x25519"] as? String ?: throw IllegalStateException("Missing pubkey_x25519")

            val port = portNum.toInt()
            if (ip.isBlank()) throw IllegalStateException("Blank public_ip")
            if (ed.length < 32) throw IllegalStateException("pubkey_ed25519 too short")
            if (x.length < 32) throw IllegalStateException("pubkey_x25519 too short")

            // Minimal “Snode-like” representation for validation
            Quad(ip, port, ed, x)
        }

        if (parsed.isEmpty()) throw IllegalStateException("service_node_states was empty")

        return parsed.size
    }

    private data class Quad(val ip: String, val port: Int, val ed: String, val x: String)
}

