import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.extensions.stdlib.capitalized
import java.io.File
import javax.inject.Inject

class RenameApkPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            androidComponents.onVariants { variant ->
                val taskProvider = project.tasks.register(
                    "rename${variant.name.capitalized()}Apk",
                    RenameApkTask::class.java,
                    variant.flavorName,
                    variant.buildType
                )

                val request = variant.artifacts.use(taskProvider)
                    .wiredWithDirectories(RenameApkTask::inputDir, RenameApkTask::outputDir)
                    .toTransformMany(SingleArtifact.APK)

                taskProvider.configure {
                    this.request.set(request)
                }
            }
        }
    }
}

abstract class RenameApkTask @Inject constructor(
    private val flavourName: String?,
    private val buildType: String?
) : DefaultTask() {
    @get:InputFiles
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val request: Property<ArtifactTransformationRequest<RenameApkTask>>

    @TaskAction
    fun run() {
        request.get()
            .submit(this) { artifact ->
                val abi = artifact.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier ?: "universal"

                val name = sequenceOf(
                    "session",
                    artifact.versionName,
                    abi,
                    flavourName,
                    buildType
                ).filterNotNull()
                    .joinToString(separator = "-", postfix = ".apk")

                val dst = outputDir.file(name).get().asFile
                File(artifact.outputFile).copyTo(dst, overwrite = true)
                dst
            }
    }
}