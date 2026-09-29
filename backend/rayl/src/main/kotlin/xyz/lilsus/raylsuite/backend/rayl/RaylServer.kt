package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError
import xyz.lilsus.raylsuite.core.raylapi.RaylBackend
import xyz.lilsus.raylsuite.core.raylapi.ServiceCatalog
import xyz.lilsus.raylsuite.core.raylapi.UsageReport

private const val MAX_USAGE_BYTES = 8 * 1024
private const val STATUS_PATH = "/v1/status"
private val json = Json { ignoreUnknownKeys = true }

/**
 * Environment:
 * - `RAYL_ENV` (required): `staging` or `production`; selects `catalog/<env>.json`.
 * - `RAYL_BLOCKED`: emergency block rules added to the catalog's, comma-separated.
 * - `DATABASE_URL`: Postgres for usage totals; without it totals only go to the log.
 * - `PORT`, `HOST`: Railway sets `PORT`; the container image sets `HOST=0.0.0.0`.
 */
fun main() {
    val environment = System.getenv("RAYL_ENV")?.takeIf { it in setOf("staging", "production") }
        ?: error("RAYL_ENV must be staging or production")
    val source = CatalogSource.load(environment).withBlocked(System.getenv("RAYL_BLOCKED"))
    val problems = source.problems()
    check(problems.isEmpty()) { "Invalid $environment catalog:\n${problems.joinToString("\n")}" }
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val port = (System.getenv("PORT") ?: "8080").toInt()
    require(port in 1..65_535) { "PORT must be between 1 and 65535" }

    val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
        install(UserAgent) { agent = "rayl-backend" }
        followRedirects = false
    }
    val board = PriceBoard(defaultPriceSources(http))
    val blocks = BlockBoard.mempool(http)
    val priceHistory = PriceHistory.mempool(http)
    val origin = if (environment ==
        "staging"
    ) {
        RaylBackend.Staging.baseUrl
    } else {
        RaylBackend.Production.baseUrl
    }
    val infoWidgets = InfoWidgets(origin, board::current, blocks::current, priceHistory::current)
    val catalog = CatalogService(source, infoWidgets.definitionSource(HttpDefinitionSource(http)))
    val usage = UsageCounter(
        knownEntry = catalog.entryIds::contains,
        sink = System.getenv("DATABASE_URL")?.takeIf(String::isNotBlank)?.let(::PostgresUsageSink)
            ?: LogUsageSink()
    )
    println(
        "rayl backend: $environment, ${source.entries.size} entries, ${source.blocked.size} block rules"
    )
    val startedAt = Instant.now().toString()
    val health = {
        BackendHealth(
            environment = environment,
            startedAt = startedAt,
            prices = board.health(),
            catalog = catalog.health(),
            blocked = source.blocked,
            usage = usage.health(),
            blocks = blocks.health(),
            priceHistory = priceHistory.health()
        )
    }

    embeddedServer(CIO, host = host, port = port) {
        raylModule(board::current, catalog::current, usage::record, health, infoWidgets)
        every("prices", 30.seconds) { board.refresh() }
        every("blocks", 30.seconds) { blocks.refresh() }
        every("price history", 30.minutes, retryInterval = 5.minutes) { priceHistory.refresh() }
        every("catalog", 1.minutes) { catalog.refresh() }
        every("usage", 1.minutes) { usage.flush() }
        monitor.subscribe(ApplicationStopping) { runBlocking { usage.flush() } }
    }.start(wait = true)
}

/** Runs [action] forever; a failed round is logged and the next one still runs. */
private fun CoroutineScope.every(
    name: String,
    interval: Duration,
    retryInterval: Duration = interval,
    action: suspend () -> Unit
) = launch {
    while (true) {
        var next = interval
        try {
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            println("$name refresh failed: $failure")
            next = retryInterval
        }
        delay(next)
    }
}

fun Application.raylModule(
    bitcoinPrices: () -> BitcoinPrices?,
    catalog: () -> ServiceCatalog = { ServiceCatalog() },
    recordUsage: (UsageReport) -> Unit = {},
    health: () -> BackendHealth? = { null },
    infoWidgets: InfoWidgets? = null
) {
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
        get(RaylApi.CATALOG_PATH) {
            call.response.header(HttpHeaders.CacheControl, "public, max-age=60")
            val full = catalog()
            val includeLinkOnly = call.request.queryParameters[RaylApi.INCLUDE_LINK_ONLY] == "true"
            call.respond(
                if (includeLinkOnly) {
                    full
                } else {
                    full.copy(
                        entries = full.entries.filter {
                            it.discoverable
                        }
                    )
                }
            )
        }
        get("/v1/widgets/{id}.json") {
            val definition = call.parameters["id"]?.let { infoWidgets?.definition(it) }
            if (definition == null) {
                call.respond(HttpStatusCode.NotFound, RaylApiError("not_found"))
            } else {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=60")
                call.respondText(definition.toString(), ContentType.Application.Json)
            }
        }
        post("/v1/widgets/{id}/status") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val id = call.parameters["id"]
            if (id == null || infoWidgets?.definition(id) == null) {
                call.respond(HttpStatusCode.NotFound, RaylApiError("not_found"))
                return@post
            }
            val body = call.receiveChannel().readBounded(MAX_USAGE_BYTES)
            val request = body?.let {
                runCatching { json.parseToJsonElement(it) }.getOrNull()
            } as? JsonObject
            val inputs = request?.get("inputs") as? JsonObject
            if (inputs == null || inputs.values.any { it !is JsonPrimitive || !it.isString } ||
                (request?.get("spec") as? JsonPrimitive)?.content != "rayl-widget/1" ||
                (request["widgetId"] as? JsonPrimitive)?.content != id
            ) {
                call.respond(HttpStatusCode.BadRequest, RaylApiError("invalid_request"))
                return@post
            }
            val status = infoWidgets.status(id)
            if (status ==
                null
            ) {
                call.respond(HttpStatusCode.ServiceUnavailable, RaylApiError("unavailable"))
            } else {
                call.respondText(status.toString(), ContentType.Application.Json)
            }
        }
        post(RaylApi.USAGE_PATH) {
            val body = call.receiveChannel().readBounded(MAX_USAGE_BYTES)
            val report = body?.let {
                runCatching { json.decodeFromString(UsageReport.serializer(), it) }.getOrNull()
            }
            if (report == null) {
                call.respond(HttpStatusCode.BadRequest, RaylApiError("invalid_report"))
            } else {
                recordUsage(report)
                call.respond(HttpStatusCode.NoContent)
            }
        }
        get(STATUS_PATH) {
            val status = health()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (status == null) call.respond(HttpStatusCode.NotFound) else call.respond(status)
        }
        get("/health") { call.respondText("ok") }
    }
}
