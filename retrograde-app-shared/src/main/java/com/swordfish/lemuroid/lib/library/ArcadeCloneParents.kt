package com.swordfish.lemuroid.lib.library

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Resolvedor de dependencias de romsets de arcade (clones -> pais, BIOS e dispositivos de hardware).
 *
 * As colecoes de arcade do acervo sao sets SPLIT: o zip do clone contem apenas o que difere do pai
 * e depende do zip do pai (ou de BIOS/dispositivos como naomi.zip, mie.zip, jvs13551.zip, qsound.zip)
 * na mesma pasta.
 *
 * Mapeamento extraido de `arcade-clone-parents.json` (empacotado em assets/).
 */
object ArcadeCloneParents {
    private const val ASSET_FILE = "arcade-clone-parents.json"
    private const val MAX_DEPTH = 25

    @Volatile
    private var clones: Map<String, List<String>>? = null

    @Volatile
    private var sistemas: Set<String> = emptySet()

    @Volatile
    private var biosNoAcervo: Set<String> = emptySet()

    @Volatile
    private var biosDatasetBase: Map<String, String> = emptyMap()

    @Synchronized
    fun init(context: Context) {
        if (clones != null) return
        try {
            context.assets.open(ASSET_FILE).use { input ->
                loadFromStream(input)
            }
        } catch (e: Exception) {
            Timber.e(e, "ArcadeCloneParents: falha ao carregar $ASSET_FILE")
            clones = emptyMap()
        }
    }

    @Synchronized
    fun loadFromStream(input: java.io.InputStream) {
        val jsonStr = InputStreamReader(input, StandardCharsets.UTF_8).readText()
        val root = Json.parseToJsonElement(jsonStr).jsonObject

        val sistSet = mutableSetOf<String>()
        root["sistemas"]?.let { el ->
            if (el is JsonArray) {
                el.forEach { sistSet.add(it.jsonPrimitive.content.lowercase()) }
            }
        }
        sistemas = sistSet

        val biosAcervoSet = mutableSetOf<String>()
        root["bios_no_acervo"]?.let { el ->
            if (el is JsonArray) {
                el.forEach { biosAcervoSet.add(it.jsonPrimitive.content.lowercase()) }
            }
        }
        biosNoAcervo = biosAcervoSet

        val baseMap = mutableMapOf<String, String>()
        root["bios_dataset_base"]?.let { el ->
            if (el is JsonObject) {
                el.forEach { (k, v) ->
                    baseMap[k.lowercase()] = v.jsonPrimitive.content.trimEnd('/')
                }
            }
        }
        biosDatasetBase = baseMap

        val clonesMap = mutableMapOf<String, List<String>>()
        root["clones"]?.let { el ->
            if (el is JsonObject) {
                el.forEach { (key, value) ->
                    when (value) {
                        is JsonArray -> {
                            val list = value.map { it.jsonPrimitive.content.lowercase() }
                            clonesMap[key.lowercase()] = list
                        }
                        else -> {
                            clonesMap[key.lowercase()] = listOf(value.jsonPrimitive.content.lowercase())
                        }
                    }
                }
            }
        }
        clones = clonesMap
        Timber.i("ArcadeCloneParents: carregadas ${clonesMap.size} regras para ${sistSet.size} sistemas")
    }

    fun appliesToSystem(systemId: String): Boolean {
        val s = systemId.lowercase()
        return sistemas.contains(s) ||
            s.startsWith("mame") ||
            s.contains("fbneo") ||
            s.contains("arcade") ||
            s.contains("neogeo") ||
            s.contains("cps") ||
            s == "naomi" ||
            s == "atomiswave"
    }

    fun biosDatasetBaseUrl(systemId: String): String? {
        val s = systemId.lowercase()
        val usesMameBios = s.startsWith("mame") || s == "naomi" || s == "atomiswave"
        return biosDatasetBase[s] ?: if (usesMameBios) biosDatasetBase["mame289"] else null
    }

    fun hasBiosInAcervo(systemId: String): Boolean {
        val s = systemId.lowercase()
        return biosNoAcervo.contains(s) || s == "mame289" || s == "naomi" || s == "atomiswave"
    }

    /**
     * Retorna a lista de dependencias (pais, BIOS, dispositivos) de [romName] (com ou sem .zip).
     * Resolve a cadeia de ancestrais em profundidade ate 25 niveis sem loops.
     */
    fun resolveAncestors(
        context: Context? = null,
        systemId: String,
        romName: String?,
    ): List<String> {
        if (romName.isNullOrBlank()) return emptyList()
        context?.let { init(it) }
        if (!appliesToSystem(systemId)) return emptyList()

        val stem = romName.lowercase().removeSuffix(".zip").removeSuffix(".7z")
        val map = clones ?: return emptyList()

        val queue = ArrayDeque<String>()
        val seen = mutableSetOf(stem)
        val result = mutableListOf<String>()

        queue.add(stem)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_DEPTH) {
            val curr = queue.removeFirst()
            visited++

            val parents = map[curr] ?: continue
            for (p in parents) {
                val clean = p.trim().lowercase()
                if (clean.isNotEmpty() && seen.add(clean)) {
                    result.add(clean)
                    queue.add(clean)
                }
            }
        }

        return result
    }
}
