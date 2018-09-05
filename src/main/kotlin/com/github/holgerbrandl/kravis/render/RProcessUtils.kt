package com.github.holgerbrandl.kravis.render

import com.github.holgerbrandl.kravis.GGPlot
import krangl.writeTSV
import java.io.*

/**
 * @author Holger Brandl
 */

enum class PlotFormat {
    PNG, SVG, EPS, JPG, PDF;

    override fun toString(): String {
        return "." + super.toString().toLowerCase()
    }

    companion object {
        @Suppress("SENSELESS_COMPARISON")
        fun isSupported(extension: String): Boolean = valueOf(extension.toUpperCase()) != null
    }
}


abstract class REngine {
    internal abstract fun runRScript(script: String)

    internal abstract fun render(plot: GGPlot, outputFile: File): File
}

internal object EngineAutodetect {

    val R_ENGINE_DEFAULT by lazy {
        // todo autodetect environment and inform user about choide
        LocalR()
    }
}

abstract class AbstractLocalREngine : REngine() {

    override fun render(plot: GGPlot, outputFile: File): File {
        val final = plot.plotCmd.joinToString("+\n")

        // save all the data
        // todo hash dfs where possible to avoid IO
        val dataIngest = plot.dataRegistry.mapValues {
            createTempFile(".txt").apply { it.value.writeTSV(this) }
        }.map { (dataVar, file) ->
            """${dataVar} = read_tsv("${file}")"""
        }.joinToString("\n")


        val rScript = """
                library(ggplot2)
                library(dplyr)
                library(readr)

                $dataIngest

                set.seed(2009)
                gg = $final

                ggsave(filename="${outputFile.absolutePath}", plot=gg)
            """.trimIndent()

        runRScript(rScript)

        require(outputFile.exists()) { System.err.println("Image generation failed") }
        return outputFile
    }
}


class LocalR : AbstractLocalREngine() {

    override fun runRScript(script: String) {
        val result = RUtils.runRScript(script)
        if (result.exitCode != 0) {
            throw LocalRenderingFailedException(result)
        }
    }
}


object RUtils {
    data class CmdResult(val exitCode: Int, val stdout: Iterable<String>, val stderr: Iterable<String>) {
        fun sout() = stdout.joinToString("\n").trim()

        fun serr() = stderr.joinToString("\n").trim()
    }


    fun runRScript(script: String): CmdResult {
        val scriptFile = createTempFile(suffix = ".R").apply { writeText(script) }

        return evalCmd("/usr/local/bin/R", listOf("--vanilla", "--quiet", "--slave", "-f", scriptFile.absolutePath))
    }


    fun evalCmd(executable: String, args: List<String>, showOutput: Boolean = false,
                redirectStdout: File? = null, redirectStderr: File? = null): CmdResult {

        try {
            val pb = ProcessBuilder(*(arrayOf(executable) + args)) //.inheritIO();
            pb.directory(File("."));
            val p = pb.start();

            val outputGobbler = StreamGobbler(p.getInputStream(), if (showOutput) System.out else null)
            val errorGobbler = StreamGobbler(p.getErrorStream(), if (showOutput) System.err else null)

            // kick them off
            errorGobbler.start()
            outputGobbler.start()

            // any error???
            val exitVal = p.waitFor()
            return CmdResult(exitVal, outputGobbler.sb.lines(), errorGobbler.sb.lines())
        } catch (t: Throwable) {
            throw RuntimeException(t)
        }
    }


    internal class StreamGobbler(var inStream: InputStream, val printStream: PrintStream?) : Thread() {
        var sb = StringBuilder()

        override fun run() {
            try {
                val isr = InputStreamReader(inStream)
                val br = BufferedReader(isr)
                for (line in br.linesJ7()) {
                    sb.append(line!! + "\n")
                    printStream?.println(line)
                }
            } catch (ioe: IOException) {
                ioe.printStackTrace()
            }
        }

        private fun BufferedReader.linesJ7(): Iterable<String> = lineSequence().toList()


        val output: String get() = sb.toString()
    }

}


open class RenderingFailedException : java.lang.RuntimeException()

class LocalRenderingFailedException(val result: RUtils.CmdResult) : RenderingFailedException() {
    override fun toString(): String = result.toString()
}