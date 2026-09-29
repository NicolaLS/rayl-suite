package xyz.lilsus.raylsuite.core.widgetspec

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The lab's v1 definitions, moved to an HTTPS origin. */
internal object WidgetFixtures {
    const val BASE = "https://lab.example"
    const val CLARO_URL = "$BASE/widgets/claro-sv-packages"
    const val PPQ_URL = "$BASE/widgets/ppq-credits"
    const val PRICE_URL = "$BASE/widgets/bitcoin-price"

    val claro = """
        {"spec":"rayl-widget/1","id":"claro-sv-packages","title":"Claro packages",
         "provider":{"name":"Claro El Salvador"},
         "inputs":[{"id":"phone","type":"phone","secret":false,"label":"Claro number","country":"SV","required":true,"placeholder":"+503 7XXX XXXX"}],
         "purchase":{"type":"product","products":[
           {"id":"p-1d","name":"Internet 1 día","description":"1 GB por 24 horas","price":{"amount":"1.00","currency":"USD"},"validity":"1 día"},
           {"id":"p-7d","name":"Internet 7 días","price":{"amount":"5.00","currency":"USD"},"validity":"7 días"},
           {"id":"p-30d","name":"Internet 30 días","price":{"amount":"15.00","currency":"USD"},"available":false}]},
         "terms":{"url":"https://paquetes2.miclaro.com.sv/portal/landing","version":"2026-09-28"},
         "invoiceUrl":"$BASE/rayl/invoice","deliveryUrl":"$BASE/rayl/delivery","validateUrl":"$BASE/rayl/validate",
         "lab":{"provider":"claro","mode":"mock"},
         "families":{"small":{"products":["p-7d"],"label":"Claro"},"medium":{"products":["p-1d","p-7d","gone"]},"huge":{}}}
    """.trimIndent()

    val ppq = """
        {"spec":"rayl-widget/1","id":"ppq-credits","title":"PPQ credits","provider":{"name":"PPQ.AI"},
         "inputs":[{"id":"apiKey","type":"text","secret":true,"label":"PPQ API key","required":true}],
         "statusUrl":"$BASE/rayl/status","validateUrl":"$BASE/rayl/validate",
         "purchase":{"type":"amount","currency":"USD","amount":{"min":"0.10","max":"1000.00","step":"0.01","presets":["5.00","10.00","20.00"]}},
         "terms":{"url":"https://ppq.ai/terms","version":"2026-09-28"},
         "invoiceUrl":"$BASE/rayl/invoice","deliveryUrl":"$BASE/rayl/delivery",
         "families":{"small":{},"medium":{"presets":["5.00","10.00"]}},
         "snapshot":{"status":{"title":"Balance","value":"$12.40","typedValue":{"type":"money","amount":"12.40","currency":"USD"}}}}
    """.trimIndent()

    val price = """
        {"spec":"rayl-widget/1","id":"bitcoin-price","title":"Bitcoin price",
         "provider":{"name":"Rayl Lab · mempool.space"},"inputs":[],
         "statusUrl":"$BASE/rayl/status","families":{"small":{}},
         "snapshot":{"status":{"title":"BTC/USD","value":"$64,000","subtitle":"mempool.space"}}}
    """.trimIndent()

    fun edited(source: String, edit: MutableMap<String, JsonElement>.() -> Unit): String {
        val root = WidgetProtocol.json.parseToJsonElement(source).jsonObject.toMutableMap()
        root.edit()
        return JsonObject(root).toString()
    }
}
