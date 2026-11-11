package com.example.pgat

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Windows limita las rutas a 260 caracteres. Si una carpeta es muy larga, la asocia a una
 * letra de unidad virtual con `subst` (no requiere administrador) y usa esa ruta corta.
 * Las asociaciones se quitan al cerrar IntelliJ.
 */
object LongPaths {
    private const val THRESHOLD = 120
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val mounts = LinkedHashMap<String, String>() // carpeta original -> letra ("Z:")

    init {
        if (isWindows) {
            Runtime.getRuntime().addShutdownHook(Thread {
                synchronized(mounts) { mounts.values.forEach { run("subst", it, "/D") } }
            })
        }
    }

    /**
     * Devuelve una ruta equivalente y corta si la original es larga (solo Windows).
     * Con force = true se mapea aunque la ruta sea corta (útil para carpetas que se van a recorrer,
     * porque sus hijos sí pueden superar el límite).
     */
    fun resolve(f: File, force: Boolean = false): File {
        if (!isWindows) return f
        val abs = norm(f.absoluteFile.path)
        if (!force && abs.length < THRESHOLD) return f

        synchronized(mounts) {
            mounts.entries.firstOrNull { (orig, _) ->
                abs.equals(orig, ignoreCase = true) || abs.startsWith("$orig\\", ignoreCase = true)
            }?.let { (orig, drive) ->
                val rest = abs.substring(orig.length).trimStart('\\')
                return File(if (rest.isEmpty()) "$drive\\" else "$drive\\$rest")
            }

            // Carpeta existente: se mapea ella misma. Archivo o carpeta nueva: se mapea su padre.
            val dir = (if (f.absoluteFile.isDirectory) f.absoluteFile else f.absoluteFile.parentFile) ?: return f
            val dirPath = norm(dir.path)
            val drive = mount(dirPath) ?: return f
            val rest = abs.substring(dirPath.length).trimStart('\\')
            return File(if (rest.isEmpty()) "$drive\\" else "$drive\\$rest")
        }
    }

    private fun mount(dirPath: String): String? {
        mounts[dirPath]?.let { return it }
        val target = File(dirPath)
        if (!target.exists() && !target.mkdirs()) return null
        for (c in 'Z' downTo 'D') {
            val drive = "$c:"
            if (drive in mounts.values || File("$drive\\").exists()) continue
            if (!run("subst", drive, dirPath)) return null
            mounts[dirPath] = drive
            return drive
        }
        return null
    }

    private fun norm(p: String) = p.replace('/', '\\').trimEnd('\\')

    private fun run(vararg args: String): Boolean = try {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }
}