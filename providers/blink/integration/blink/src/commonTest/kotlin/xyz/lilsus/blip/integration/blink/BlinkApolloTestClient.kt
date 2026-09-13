package xyz.lilsus.blip.integration.blink

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.parseResponse
import com.apollographql.apollo.network.NetworkTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okio.Buffer

internal class BlinkApolloTestTransport(private val handler: (ApolloRequest<*>) -> ApolloResponse<*>) : NetworkTransport {
    override fun <D : Operation.Data> execute(request: ApolloRequest<D>): Flow<ApolloResponse<D>> {
        @Suppress("UNCHECKED_CAST")
        return flowOf(handler(request) as ApolloResponse<D>)
    }

    override fun dispose() = Unit
}

internal fun createBlinkApolloTestClient(transport: BlinkApolloTestTransport): ApolloClient = ApolloClient.Builder()
    .networkTransport(transport)
    // Keep the synchronous fake transport on the test coroutine, including under virtual time.
    .dispatcher(Dispatchers.Unconfined)
    .build()

internal fun <D : Operation.Data> ApolloRequest<D>.responseFromJson(json: String): ApolloResponse<D> = operation.parseResponse(
    jsonReader = BufferedSourceJsonReader(Buffer().writeUtf8(json)),
    requestUuid = requestUuid
)
