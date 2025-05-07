import java.io.File
import java.io.DataOutputStream
import java.io.FileOutputStream

abstract class GenerateCountrBlocksTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val inputFile = File(projectDir, "geolite2_country_blocks_ipv4.csv")
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
