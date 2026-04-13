package me.bestvibes.exifdatefixer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

class ProcessUtils {
    companion object {
        suspend fun runCommand(command: ArrayList<String>, env: Map<String, String> = emptyMap()): String {
            return withContext(Dispatchers.Default) {
                val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
                if (env.isNotEmpty()) {
                    processBuilder.environment().putAll(env)
                }
                val process = processBuilder.start()
                process.waitFor()

                val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)

                process.destroy()

                return@withContext stdout
            }
        }
    }
}