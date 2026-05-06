package pl.danielniemiec.properbackup.agent.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import java.nio.file.Files
import java.nio.file.Path

object ConfigLoader {

    @JvmStatic
    fun loadGlobal(globalYml: Path): GlobalConfig {
        if (!Files.exists(globalYml)) return GlobalConfig()
        Files.newInputStream(globalYml).use { `in` ->
            val yaml = Yaml(Constructor(GlobalConfig::class.java, LoaderOptions()))
            return yaml.load<GlobalConfig>(`in`) ?: GlobalConfig()
        }
    }

    @JvmStatic
    fun loadServices(confDir: Path): List<ServiceConfig> {
        val result = ArrayList<ServiceConfig>()
        if (!Files.exists(confDir)) return result
        Files.list(confDir).use { files ->
            files.filter { it.toString().endsWith(".yml") }
                .filter { it.fileName.toString() != "global.yml" }
                .sorted()
                .forEach { p ->
                    try {
                        Files.newInputStream(p).use { `in` ->
                            val yaml = Yaml(Constructor(ServiceConfig::class.java, LoaderOptions()))
                            val svc = yaml.load<ServiceConfig>(`in`)
                            if (svc != null && svc.enabled && svc.paths != null && svc.paths!!.isNotEmpty()) {
                                result.add(svc)
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("WARN: could not load $p: ${e.message}")
                    }
                }
        }
        return result
    }
}
