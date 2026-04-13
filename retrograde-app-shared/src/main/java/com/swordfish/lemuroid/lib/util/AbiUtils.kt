package com.swordfish.lemuroid.lib.util

import android.content.Context
import android.os.Build
import java.io.File

object AbiUtils {
    /**
     * Retorna a ABI canônica do processo do app, no formato esperado pelo repositório de cores
     * (ex: "arm64-v8a", "armeabi-v7a", "x86_64", "x86").
     *
     * Tenta obter a ABI via reflexão no campo `primaryCpuAbi` do ApplicationInfo (que existe em
     * Android runtime mas não é exposto na API pública do SDK). Se não disponível, usa o nome da
     * pasta de bibliotecas nativas com normalização. Ambos os resultados são normalizados para
     * garantir nomes canônicos (ex: "arm64" → "arm64-v8a").
     */
    fun getProcessAbi(context: Context): String {
        // Tenta ler via reflexão o campo primaryCpuAbi (campo real do runtime Android).
        val primaryAbi = runCatching {
            val field = context.applicationInfo.javaClass.getField("primaryCpuAbi")
            field.isAccessible = true
            field.get(context.applicationInfo) as? String
        }.getOrNull()

        if (!primaryAbi.isNullOrBlank()) {
            return normalizeAbi(primaryAbi)
        }

        // Fallback: nome da pasta de bibliotecas nativas. Em algumas ROMs pode ser um apelido
        // curto (ex: "arm64"), então normalizamos.
        val nativeLibDirName = File(context.applicationInfo.nativeLibraryDir).name
        val normalizedFromDir = normalizeAbi(nativeLibDirName)
        if (normalizedFromDir.isNotBlank()) {
            return normalizedFromDir
        }

        // Último recurso: primeira ABI suportada pelo hardware.
        return Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"
    }

    /**
     * Mapeia apelidos curtos de ABI para o nome canônico usado nos paths do repositório de cores.
     */
    fun normalizeAbi(abi: String): String =
        when (abi.lowercase()) {
            "arm64", "arm64-v8a" -> "arm64-v8a"
            "arm", "armeabi-v7a", "armeabi" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> abi
        }
    /**
     * Verifica se o arquivo ELF é compatível com a ABI esperada (32 vs 64 bits).
     * Lê os primeiros 5 bytes do cabeçalho ELF para validar o Magic Number e a Classe (32/64 bits).
     */
    fun isElfCompatible(file: File, expectedAbi: String): Boolean {
        if (!file.exists() || file.length() < 6) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(6)
                if (stream.read(header) != 6) return false

                // BUG 32 fix: Use and 0xFF to avoid sign-extension on bytes > 127
                // ELF Magic: 0x7F 'E' 'L' 'F'
                if (header[0].toInt() and 0xFF != 0x7F ||
                    header[1].toInt() and 0xFF != 0x45 ||
                    header[2].toInt() and 0xFF != 0x4C ||
                    header[3].toInt() and 0xFF != 0x46) return false

                val elfClass = header[4].toInt() and 0xFF // 1 = 32-bit, 2 = 64-bit

                // BUG 31 fix: Check endianness (EI_DATA byte 5: 1=little-endian, 2=big-endian)
                // ARM ABIs are always little-endian; x86 is little-endian.
                // Accept both to be forward-compatible, but reject invalid values.
                val elfData = header[5].toInt() and 0xFF
                if (elfData != 1 && elfData != 2) return false

                val is64BitAbi = expectedAbi.contains("64") || expectedAbi == "x86_64" || expectedAbi == "arm64-v8a"

                if (is64BitAbi) elfClass == 2 else elfClass == 1
            }
        } catch (e: Exception) {
            false
        }
    }
}
