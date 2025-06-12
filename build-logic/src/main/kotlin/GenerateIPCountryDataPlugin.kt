import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.HasUnitTest
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.internal.sharedruntime.codegen.pluginEntriesFrom
import org.gradle.kotlin.dsl.register
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.File

class GenerateIPCountryDataPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            androidComponents.onVariants { variant ->
                val task = project.tasks.register("generate${variant.name.capitalized()}IpCountryData", GenerateCountryBlocksTask::class.java) {
                    outputDir.set(project.layout.buildDirectory.dir("generated/${variant.name}"))
                }

                variant.sources.assets?.addGeneratedSourceDirectory(
                    task,
                    GenerateCountryBlocksTask::outputDir
                )

                // Also add the generated source directory to the unit test sources
                (variant as? HasUnitTest)?.unitTest?.sources?.resources?.addGeneratedSourceDirectory(
                    task,
                    GenerateCountryBlocksTask::outputDir
                )
            }
        }

    }
}


abstract class GenerateCountryBlocksTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val inputFile = File(project.projectDir, "geolite2_country_blocks_ipv4.csv")
        check(inputFile.exists()) { "$inputFile does not exist and it is required" }

        val outputDir = outputDir.get().asFile

        outputDir.mkdirs()

        val outputFile = File(outputDir, "geolite2_country_blocks_ipv4.bin")

        // Create a DataOutputStream to write binary data
        DataOutputStream(FileOutputStream(outputFile)).use { out ->
            inputFile.useLines { lines ->
                var prevCode = -1
                lines.drop(1).forEach { line ->
                    runCatching {
                        val ints = line.split(".", "/", ",")
                        val code = ints[5].toInt().also { if (it == prevCode) return@forEach }
                        val ip = ints.take(4).fold(0) { acc, s -> acc shl 8 or s.toInt() }

                        out.writeInt(ip)
                        out.writeInt(code)

                        prevCode = code
                    }
                }
            }
        }

        println("Processed data written to: ${outputFile.absolutePath}")
    }
}