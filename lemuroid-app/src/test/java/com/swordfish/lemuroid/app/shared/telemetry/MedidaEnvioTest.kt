package com.swordfish.lemuroid.app.shared.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MedidaEnvioTest {
    private class MarcaEmMemoria : MedidaEnvio.Marca {
        val marcas = HashMap<String, String>()

        override fun versaoMarcada(id: String): String? = marcas[id]

        override fun marcar(
            id: String,
            versao: String,
        ) {
            marcas[id] = versao
        }
    }

    private class TransporteFalso(var resposta: MedidaEnvio.Resposta?) : MedidaEnvio.Transporte {
        val enviados = ArrayList<Pair<String, String>>()

        override fun post(
            url: String,
            json: String,
        ): MedidaEnvio.Resposta? {
            enviados += url to json
            return resposta
        }
    }

    private val marca = MarcaEmMemoria()
    private val transporte = TransporteFalso(MedidaEnvio.Resposta(200, "ok"))
    private var versao = "1.17.32"

    private fun envio() =
        MedidaEnvio(
            marca = marca,
            transporte = transporte,
            endpoint = { MedidaEnvio.endpointDeMedida("https://exemplo.test/logErr") },
            versao = { versao },
            plataforma = "Android 14 (sdk 34); Fabricante Modelo; arm64-v8a",
            executar = { it() },
            log = {},
        )

    @Test
    fun chamar_duas_vezes_envia_uma() {
        val e = envio()

        e.medida("bug-12_tela", MedidaEnvio.OK)
        e.medida("bug-12_tela", MedidaEnvio.OK)

        assertEquals(1, transporte.enviados.size)
        assertEquals("1.17.32", marca.marcas["bug-12_tela"])
    }

    @Test
    fun versao_nova_envia_de_novo() {
        envio().medida("bug-12", MedidaEnvio.OK)

        versao = "1.17.33"
        envio().medida("bug-12", MedidaEnvio.OK)

        assertEquals(2, transporte.enviados.size)
        assertEquals("1.17.33", marca.marcas["bug-12"])
    }

    @Test
    fun erro_500_nao_marca_e_reenvia() {
        val e = envio()
        transporte.resposta = MedidaEnvio.Resposta(500, "error")
        e.medida("bug-12", MedidaEnvio.FALHOU)
        assertTrue(marca.marcas.isEmpty())

        transporte.resposta = null // rede fora tambem nao marca
        e.medida("bug-12", MedidaEnvio.FALHOU)
        assertTrue(marca.marcas.isEmpty())

        transporte.resposta = MedidaEnvio.Resposta(200, "ok\n")
        e.medida("bug-12", MedidaEnvio.FALHOU)

        assertEquals(3, transporte.enviados.size)
        assertEquals("1.17.32", marca.marcas["bug-12"])
    }

    @Test
    fun resposta_200_sem_corpo_ok_nao_marca() {
        transporte.resposta = MedidaEnvio.Resposta(200, "<html>proxy</html>")

        envio().medida("bug-12", MedidaEnvio.OK)

        assertTrue(marca.marcas.isEmpty())
    }

    @Test
    fun recusa_400_marca_e_nao_reenvia() {
        val e = envio()
        transporte.resposta = MedidaEnvio.Resposta(400, "campo invalido: versao")

        e.medida("bug-12", MedidaEnvio.OK)
        e.medida("bug-12", MedidaEnvio.OK)

        assertEquals(1, transporte.enviados.size)
        assertEquals("1.17.32", marca.marcas["bug-12"])
    }

    @Test
    fun id_invalido_nao_envia() {
        val e = envio()

        e.medida("bug/12", MedidaEnvio.OK)
        e.medida("", MedidaEnvio.OK)
        e.medida("a".repeat(161), MedidaEnvio.OK)
        e.medida("bug-12", "talvez")
        versao = "unknown"
        e.medida("bug-12", MedidaEnvio.OK)

        assertTrue(transporte.enviados.isEmpty())
        assertTrue(marca.marcas.isEmpty())
    }

    @Test
    fun id_em_voo_nao_sai_duas_vezes() {
        val pendentes = ArrayList<() -> Unit>()
        val e =
            MedidaEnvio(
                marca = marca,
                transporte = transporte,
                endpoint = { MedidaEnvio.ENDPOINT_PADRAO },
                versao = { versao },
                plataforma = "Android",
                executar = { pendentes += it },
                log = {},
            )

        e.medida("bug-12", MedidaEnvio.OK)
        e.medida("bug-12", MedidaEnvio.OK)

        assertEquals(1, pendentes.size)
        pendentes.single()()
        assertEquals(1, transporte.enviados.size)
    }

    @Test
    fun corpo_tem_os_campos_do_backend() {
        envio().medida("bug-12", MedidaEnvio.FALHOU)

        val (url, json) = transporte.enviados.single()
        assertEquals("https://exemplo.test/logMedida", url)
        val corpo = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("id", "resultado", "project", "versao", "plataforma"), corpo.keys)
        assertFalse(corpo.containsKey("account_id"))
        assertEquals("bug-12", corpo["id"]!!.jsonPrimitive.content)
        assertEquals("falhou", corpo["resultado"]!!.jsonPrimitive.content)
        assertEquals("retrogamesystem", corpo["project"]!!.jsonPrimitive.content)
        assertFalse(corpo["project"]!!.jsonPrimitive.content.contains('/'))
        assertEquals("1.17.32", corpo["versao"]!!.jsonPrimitive.content)
        assertEquals(
            "Android 14 (sdk 34); Fabricante Modelo; arm64-v8a",
            corpo["plataforma"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun endpoint_sem_sufixo_logErr_usa_o_padrao() {
        assertEquals(
            "https://digitalstoregames.pythonanywhere.com/logMedida",
            MedidaEnvio.endpointDeMedida("http://192.168.0.10:8080/outro"),
        )
        assertEquals(MedidaEnvio.ENDPOINT_PADRAO, MedidaEnvio.endpointDeMedida(null))
        assertEquals(
            "http://10.0.2.2:5000/logMedida",
            MedidaEnvio.endpointDeMedida("http://10.0.2.2:5000/logErr"),
        )
    }
}
