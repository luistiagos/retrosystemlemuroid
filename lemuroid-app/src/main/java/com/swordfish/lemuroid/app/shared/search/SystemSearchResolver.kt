package com.swordfish.lemuroid.app.shared.search

import android.content.Context
import com.swordfish.lemuroid.lib.library.GameSystem
import java.text.Normalizer
import java.util.Locale

class SystemSearchResolver(private val context: Context) {

    private val commonAliases: Map<String, List<String>> = mapOf(
        "snes" to listOf("snes"),
        "super nintendo" to listOf("snes"),
        "super famicom" to listOf("snes"),
        "nes" to listOf("nes"),
        "nintendinho" to listOf("nes"),
        "famicom" to listOf("nes", "fds"),
        "mega drive" to listOf("md", "scd"),
        "genesis" to listOf("md", "scd"),
        "sega cd" to listOf("scd"),
        "master system" to listOf("sms"),
        "game gear" to listOf("gg"),
        "psx" to listOf("psx"),
        "ps1" to listOf("psx"),
        "playstation" to listOf("psx"),
        "playstation portable" to listOf("psp"),
        "psp" to listOf("psp"),
        "n64" to listOf("n64"),
        "nintendo 64" to listOf("n64"),
        "game boy" to listOf("gb", "gbc", "gba"),
        "gameboy" to listOf("gb", "gbc", "gba"),
        "gba" to listOf("gba"),
        "game boy advance" to listOf("gba"),
        "gameboy advance" to listOf("gba"),
        "gbc" to listOf("gbc"),
        "game boy color" to listOf("gbc"),
        "gameboy color" to listOf("gbc"),
        "nds" to listOf("nds"),
        "nintendo ds" to listOf("nds"),
        "ds" to listOf("nds"),
        "3ds" to listOf("3ds"),
        "nintendo 3ds" to listOf("3ds"),
        "arcade" to listOf("mame2003plus", "fbneo", "cps1", "cps2", "cps3", "neogeo"),
        "fliperama" to listOf("mame2003plus", "fbneo", "cps1", "cps2", "cps3", "neogeo"),
        "mame" to listOf("mame2003plus", "fbneo"),
        "neogeo" to listOf("neogeo"),
        "neo geo" to listOf("neogeo", "ngp"),
        "dreamcast" to listOf("dc"),
        "atari" to listOf("atari2600", "atari5200", "atari7800"),
        "atari 2600" to listOf("atari2600"),
        "atari 5200" to listOf("atari5200"),
        "atari 7800" to listOf("atari7800"),
        "gamecube" to listOf("ngc"),
        "wonderswan" to listOf("ws", "wsc"),
        "pc engine" to listOf("pce", "pcecd"),
        "turbografx" to listOf("pce", "pcecd"),
    )

    fun resolveSystemIds(query: String): List<String> {
        val cleanQuery = normalize(query.trim())
        if (cleanQuery.isEmpty()) return emptyList()

        val tokens = cleanQuery.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val matchedIds = mutableSetOf<String>()

        for ((alias, ids) in commonAliases) {
            val normalizedAlias = normalize(alias)
            // 1. Casamento exato ou prefixo da query inteira (ex: "snes", "super nintendo")
            if (cleanQuery == normalizedAlias || normalizedAlias.startsWith(cleanQuery)) {
                matchedIds.addAll(ids)
                continue
            }
            // 2. Apelido multi-palavra contido na query inteira (ex: "jogos super nintendo")
            if (normalizedAlias.contains(" ") && cleanQuery.contains(normalizedAlias)) {
                matchedIds.addAll(ids)
                continue
            }
            // 3. Apelido de palavra única coincidente com algum token exato da busca (ex: "mario snes" -> "snes")
            if (tokens.any { it == normalizedAlias }) {
                matchedIds.addAll(ids)
            }
        }

        for (system in GameSystem.all()) {
            val title = runCatching { normalize(context.getString(system.titleResId)) }.getOrDefault("")
            val shortTitle = runCatching { normalize(context.getString(system.shortTitleResId)) }.getOrDefault("")
            val dbname = system.id.dbname.lowercase(Locale.ROOT)

            if (dbname == cleanQuery ||
                tokens.any { it == dbname } ||
                (title.isNotEmpty() && (title == cleanQuery || title.startsWith(cleanQuery) || (title.contains(" ") && cleanQuery.contains(title)))) ||
                (shortTitle.isNotEmpty() && (shortTitle == cleanQuery || tokens.any { it == shortTitle }))
            ) {
                matchedIds.add(system.id.dbname)
            }
        }

        return matchedIds.toList()
    }

    private fun normalize(str: String): String {
        val normalized = Normalizer.normalize(str, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .trim()
    }
}
