package com.zoomrx.hybridappsync

import android.app.Application
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ZipUtil private constructor(private val appContext: Application) {

    companion object {
        private var instance: ZipUtil? = null

        @Synchronized
        @JvmStatic
        fun getInstance(appContext: Application): ZipUtil {
            return if (instance == null) {
                ZipUtil(appContext)
            } else {
                instance as ZipUtil
            }
        }
    }

    private val bufferSize = 8192

    fun unzip(source: String, destination: String, isAsset: Boolean): Boolean {
        val zipInputStream = constructZipInputStream(source, isAsset)
        return extract(zipInputStream, destination)
    }

    private fun constructZipInputStream(filepath: String, isAsset: Boolean): ZipInputStream {
        return if (isAsset) {
            ZipInputStream(appContext.assets.open(filepath))
        } else {
            ZipInputStream(File(filepath).inputStream())
        }
    }

    private fun extract(zipInputStream: ZipInputStream, location: String): Boolean {
        try {
            var zipEntry: ZipEntry?
            while (zipInputStream.nextEntry.also { zipEntry = it } != null) {
                val innerFileName = location + zipEntry!!.name
                val innerFile = File(innerFileName)
                if (zipEntry!!.isDirectory) {
                    innerFile.mkdirs()
                } else {
                    if (innerFileName.lastIndexOf('/') != -1) {
                        val srcFileDir = File(innerFileName.substring(0, innerFileName.lastIndexOf('/')))
                        srcFileDir.mkdirs()
                    }
                    val outputStream = FileOutputStream(innerFile)
                    val bufferedOutputStream = BufferedOutputStream(
                        outputStream, bufferSize)
                    val buffer = ByteArray(bufferSize)
                    var count: Int
                    while (zipInputStream.read(buffer).also { count = it } != -1) {
                        bufferedOutputStream.write(buffer, 0, count)
                    }
                    bufferedOutputStream.flush()
                    bufferedOutputStream.close()
                    outputStream.close()
                }
                zipInputStream.closeEntry()
            }
            zipInputStream.close()
            return true
        } catch (e: IOException) {
        }
        return false
    }
}