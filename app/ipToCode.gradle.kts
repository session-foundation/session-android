import java.io.File
import java.io.DataOutputStream



class GenerateIPFilePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val androidComponents = project.extensions.getByType<AndroidComponentsExtension>()
        androidComponents.onVariants { variant ->
            println("Got ${variant.name}")
        }
    }
}

apply<GenerateIPFilePlugin>()