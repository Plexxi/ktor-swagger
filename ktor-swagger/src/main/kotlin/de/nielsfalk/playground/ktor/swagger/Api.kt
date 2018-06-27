package de.nielsfalk.playground.ktor.swagger

import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.Created
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.locations.delete
import io.ktor.locations.get
import io.ktor.locations.post
import io.ktor.locations.put
import io.ktor.pipeline.ContextDsl
import io.ktor.pipeline.PipelineContext
import io.ktor.request.receive
import io.ktor.routing.Route
import io.ktor.routing.application
import kotlin.reflect.KClass

data class Metadata(
    val responses: Map<HttpStatusCode, KClass<*>>,
    val summary: String? = null,
    val headers: KClass<*>? = null,
    val parameter: KClass<*>? = null
) {
    inline fun <reified T> header(): Metadata = copy(headers = T::class)

    inline fun <reified T> parameter(): Metadata = copy(parameter = T::class)
}

fun String.responds(vararg pairs: Pair<HttpStatusCode, KClass<*>>): Metadata = Metadata(responses = mapOf(*pairs), summary = this)

fun responds(pair: Pair<HttpStatusCode, KClass<*>>) = Metadata(responses = mapOf(pair))
fun responses(vararg pairs: Pair<HttpStatusCode, KClass<*>>) = Metadata(responses = mapOf(*pairs))

inline fun <reified T> ok(): Pair<HttpStatusCode, KClass<*>> = OK to T::class
inline fun <reified T> created(): Pair<HttpStatusCode, KClass<*>> = Created to T::class
inline fun notFound(): Pair<HttpStatusCode, KClass<*>> = NotFound to Unit::class

@ContextDsl
inline fun <reified LOCATION : Any, reified ENTITY : Any> Route.post(metadata: Metadata, noinline body: suspend PipelineContext<Unit, ApplicationCall>.(LOCATION, ENTITY) -> Unit): Route {
    application.swagger.apply {
        metadata.apply<LOCATION, ENTITY>(HttpMethod.Post)
    }

    return post<LOCATION> {
        body(this, it, call.receive())
    }
}

@ContextDsl
inline fun <reified LOCATION : Any, reified ENTITY : Any> Route.put(metadata: Metadata, noinline body: suspend PipelineContext<Unit, ApplicationCall>.(LOCATION, ENTITY) -> Unit): Route {
    application.swagger.apply {
        metadata.apply<LOCATION, ENTITY>(HttpMethod.Put)
    }
    return put<LOCATION> {
        body(this, it, call.receive())
    }
}

@ContextDsl
inline fun <reified LOCATION : Any> Route.get(metadata: Metadata, noinline body: suspend PipelineContext<Unit, ApplicationCall>.(LOCATION) -> Unit): Route {
    application.swagger.apply {
        metadata.apply<LOCATION, Unit>(HttpMethod.Get)
    }
    return get(body)
}

@ContextDsl
inline fun <reified LOCATION : Any> Route.delete(metadata: Metadata, noinline body: suspend PipelineContext<Unit, ApplicationCall>.(LOCATION) -> Unit): Route {
    application.swagger.apply {
        metadata.apply<LOCATION, Unit>(HttpMethod.Delete)
    }
    return delete(body)
}
