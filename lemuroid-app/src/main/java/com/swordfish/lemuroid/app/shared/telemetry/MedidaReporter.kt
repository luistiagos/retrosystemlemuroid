package com.swordfish.lemuroid.app.shared.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Medida de fechamento (pipeline-global, T27/T28): diz ao motor que o caso de um bug/feature
 * aconteceu nesta versao e deu `ok` ou `falhou`. Vai para `/logMedida`, nunca para `/logErr`.
 *
 * **O id vai literal na chamada** (`medida("bug-123-tela-preta", MedidaEnvio.OK)`), nunca montado em
 * runtime: o validador do motor acha a medida viva por `git grep -F` do id.
 *
 * Uma vez por instalacao e versao (marca em `medida_prefs`, chave = id, valor = versao). Nunca lanca,
 * nunca bloqueia quem chama, respeita o kill-switch da telemetria de erro. Sem dado pessoal e sem
 * `account_id` (o app nao tem conta).
 */
fun medida(
    id: String,
    resultado: String,
) {
    MedidaReporter.medida(id, resultado)
}

object MedidaReporter {
    private const val TAG = "Medida"
    private const val PREFS_NAME = "medida_prefs"
    private const val TIMEOUT_MS = 5000
    private const val MAX_CORPO_RESPOSTA = 4096

    @Volatile private var envio: MedidaEnvio? = null

    @JvmStatic
    fun medida(
        id: String,
        resultado: String,
    ) {
        try {
            if (!TelemetryReporter.isEnabled()) return
            val ctx = TelemetryReporter.contexto()
            if (ctx == null) {
                Log.w(TAG, "telemetria nao iniciada; medida $id nao enviada")
                return
            }
            envioPara(ctx).medida(id, resultado)
        } catch (ignored: Throwable) {
            // telemetria nunca muda o comportamento do app
        }
    }

    @Synchronized
    private fun envioPara(ctx: Context): MedidaEnvio =
        envio ?: MedidaEnvio(
            marca = PrefsMarca(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
            transporte = HttpTransporte,
            endpoint = { MedidaEnvio.endpointDeMedida(TelemetryReporter.endpointConfigurado()) },
            versao = { TelemetryReporter.versaoDoApp() },
            plataforma = plataforma(),
            executar = { bloco -> TelemetryReporter.iniciarEnvio("Lemuroid-Medida", bloco) },
            log = { Log.i(TAG, it) },
        ).also { envio = it }

    private fun plataforma(): String =
        try {
            "Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}); " +
                "${Build.MANUFACTURER} ${Build.MODEL}; ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
        } catch (e: Throwable) {
            "Android"
        }

    private class PrefsMarca(private val prefs: android.content.SharedPreferences) : MedidaEnvio.Marca {
        override fun versaoMarcada(id: String): String? = prefs.getString(id, null)

        override fun marcar(
            id: String,
            versao: String,
        ) {
            // commit: ja estamos no thread de envio, e a marca tem de sobreviver a um kill logo depois.
            prefs.edit().putString(id, versao).commit()
        }
    }

    private object HttpTransporte : MedidaEnvio.Transporte {
        override fun post(
            url: String,
            json: String,
        ): MedidaEnvio.Resposta? {
            var conn: HttpURLConnection? = null
            return try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.useCaches = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
                val codigo = conn.responseCode
                val stream = if (codigo in 200..299) conn.inputStream else conn.errorStream
                MedidaEnvio.Resposta(codigo, lerCorpo(stream))
            } catch (e: Throwable) {
                null
            } finally {
                try {
                    conn?.disconnect()
                } catch (ignored: Throwable) {
                    // nada a fazer
                }
            }
        }

        private fun lerCorpo(stream: InputStream?): String {
            if (stream == null) return ""
            return stream.use {
                val bytes = it.readBytes()
                String(bytes, 0, minOf(bytes.size, MAX_CORPO_RESPOSTA), StandardCharsets.UTF_8)
            }
        }
    }
}

/**
 * Nucleo da medida, sem Android: marca e transporte por interface para ser testado na JVM.
 *
 * Regras (T28): id fora de `[A-Za-z0-9_-]{1,160}`, resultado fora de `ok|falhou` ou versao
 * desconhecida = nao envia. Marca por (id, versao) gravada **so** com `200` + corpo `ok`; `400` marca
 * tambem (o servidor recusaria de novo nesta versao); `500`/rede/timeout nao marca (a proxima
 * ocorrencia tenta de novo). O mesmo id nao sai duas vezes em paralelo no mesmo processo.
 */
internal class MedidaEnvio(
    private val marca: Marca,
    private val transporte: Transporte,
    private val endpoint: () -> String,
    private val versao: () -> String?,
    plataforma: String,
    private val executar: (() -> Unit) -> Unit,
    private val log: (String) -> Unit,
) {
    interface Marca {
        fun versaoMarcada(id: String): String?

        fun marcar(
            id: String,
            versao: String,
        )
    }

    interface Transporte {
        /** POST do [json]; `null` = rede/timeout. Nunca lanca. */
        fun post(
            url: String,
            json: String,
        ): Resposta?
    }

    data class Resposta(val codigo: Int, val corpo: String)

    private val plataforma = plataforma.take(MAX_PLATAFORMA)
    private val emVoo = Collections.synchronizedSet(HashSet<String>())

    /** Valida e despacha o envio pelo [executar]. Nunca lanca. */
    fun medida(
        id: String,
        resultado: String,
    ) {
        try {
            if (!ID_VALIDO.matches(id)) {
                log("medida recusada: id invalido '${id.take(200)}'")
                return
            }
            if (resultado != OK && resultado != FALHOU) {
                log("medida $id recusada: resultado invalido '${resultado.take(40)}'")
                return
            }
            if (!emVoo.add(id)) return
            try {
                executar {
                    try {
                        enviar(id, resultado)
                    } catch (ignored: Throwable) {
                        // nunca lanca
                    } finally {
                        emVoo.remove(id)
                    }
                }
            } catch (e: Throwable) {
                emVoo.remove(id)
            }
        } catch (ignored: Throwable) {
            // nunca lanca
        }
    }

    private fun enviar(
        id: String,
        resultado: String,
    ) {
        val v = versao()?.trim().orEmpty()
        if (v.isEmpty() || v.equals("unknown", ignoreCase = true) || v.length > MAX_VERSAO) {
            log("medida $id nao enviada: versao desconhecida")
            return
        }
        if (marca.versaoMarcada(id) == v) return

        val resposta = transporte.post(endpoint(), corpo(id, resultado, v))
        when {
            resposta == null -> log("medida $id: sem rede, tenta na proxima ocorrencia")
            resposta.codigo == 200 && resposta.corpo.trim() == "ok" -> {
                marca.marcar(id, v)
                log("medida $id=$resultado enviada (versao $v)")
            }
            resposta.codigo == 400 -> {
                marca.marcar(id, v)
                log("medida $id recusada pelo servidor: ${resposta.corpo.trim().take(200)}")
            }
            else -> log("medida $id: servidor respondeu ${resposta.codigo}, tenta na proxima ocorrencia")
        }
    }

    internal fun corpo(
        id: String,
        resultado: String,
        versao: String,
    ): String =
        buildJsonObject {
            put("id", id)
            put("resultado", resultado)
            put("project", PROJECT)
            put("versao", versao)
            put("plataforma", plataforma)
        }.toString()

    companion object {
        const val OK = "ok"
        const val FALHOU = "falhou"

        /** Prefixo do `/logErr` sem o `/<componente>`: o backend recusa barra no `project` da medida. */
        const val PROJECT = "retrogamesystem"
        const val ENDPOINT_PADRAO = "https://digitalstoregames.pythonanywhere.com/logMedida"

        private const val MAX_VERSAO = 64
        private const val MAX_PLATAFORMA = 255
        private val ID_VALIDO = Regex("[A-Za-z0-9_-]{1,160}")

        /** O endpoint de erro configurado com `/logErr` trocado por `/logMedida`; sem o sufixo, o padrao. */
        fun endpointDeMedida(endpointDeErro: String?): String {
            val e = endpointDeErro?.trim().orEmpty()
            return if (e.endsWith("/logErr")) e.removeSuffix("/logErr") + "/logMedida" else ENDPOINT_PADRAO
        }
    }
}
