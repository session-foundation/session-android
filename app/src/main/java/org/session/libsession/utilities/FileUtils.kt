package org.session.libsession.utilities

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.AssertionError
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object FileUtils {

    @JvmStatic
    @Throws(IOException::class)
    fun getFileDigest(fin: FileInputStream): ByteArray? {
        try {
            val digest = MessageDigest.getInstance("SHA256")

            val buffer = ByteArray(4096)
            var read = 0

            while ((fin.read(buffer, 0, buffer.size).also { read = it }) != -1) {
                digest.update(buffer, 0, read)
            }

            return digest.digest()
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
    }

    @Throws(IOException::class)
    fun deleteDirectoryContents(directory: File?) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) return

        val files = directory.listFiles()

        if (files != null) {
            for (file in files) {
                if (file.isDirectory()) deleteDirectory(file)
                else file.delete()
            }
        }
    }

    @Throws(IOException::class)
    fun deleteDirectory(directory: File?) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return
        }
        deleteDirectoryContents(directory)
        directory.delete()
    }
}
