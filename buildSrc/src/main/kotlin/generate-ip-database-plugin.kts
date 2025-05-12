class GenerateIpDatabasePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val androidComponents = project.extensions.getByType<AndroidComponentsExtension>()
        androidComponents.onVariants { variant ->
            println("Got ${variant.name}")
        }
    }
}

apply<GenerateIpDatabasePlugin>()