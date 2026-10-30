package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.backend.club.ClubConfig
import xyz.lilsus.raylsuite.backend.club.TreasuryConfig
import xyz.lilsus.raylsuite.backend.club.startClub
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError

private val REFRESH_INTERVAL = 30.seconds

/**
 * Railway sets `PORT`; the container image sets `HOST=0.0.0.0`. Rayl Club is served when its
 * environment is configured ([ClubConfig]).
 */
fun main() {
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val port = (System.getenv("PORT") ?: "8080").toInt()
    require(port in 1..65_535) { "PORT must be between 1 and 65535" }
    val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
        install(UserAgent) { agent = "rayl-backend" }
    }
    val board = PriceBoard(defaultPriceSources(http))
    val clubConfig = ClubConfig.fromEnvironment()
    embeddedServer(CIO, host = host, port = port) {
        raylModule(board::current)
        if (clubConfig == null) {
            log.info("Rayl Club disabled: DATABASE_URL and SUPABASE_URL are not both set")
        } else {
            startClub(clubConfig, TreasuryConfig.fromEnvironment()) {
                board.current()?.prices?.get("USD")?.toBigDecimal()
            }
        }
        launch {
            while (true) {
                board.refresh()
                delay(REFRESH_INTERVAL)
            }
        }
    }.start(wait = true)
}

fun Application.raylModule(bitcoinPrices: () -> BitcoinPrices?) {
    install(ContentNegotiation) { json() }
    routing {
        get(RaylApi.BITCOIN_PRICES_PATH) {
            val prices = bitcoinPrices()
            if (prices == null) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.ServiceUnavailable, RaylApiError("prices_unavailable"))
            } else {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=15")
                call.respond(prices)
            }
        }
        get("/health") { call.respondText("ok") }
    }
}
