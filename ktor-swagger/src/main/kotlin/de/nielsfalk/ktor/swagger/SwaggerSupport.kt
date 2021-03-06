package de.nielsfalk.ktor.swagger

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationFeature
import io.ktor.application.call
import io.ktor.client.call.TypeInfo
import io.ktor.client.call.typeInfo
import io.ktor.http.HttpMethod
import io.ktor.locations.Location
import io.ktor.pipeline.PipelineContext
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.util.AttributeKey
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

class SwaggerSupport(
    val swagger: Swagger
) {
    companion object Feature : ApplicationFeature<Application, SwaggerUiConfiguration, SwaggerSupport> {
        override val key = AttributeKey<SwaggerSupport>("SwaggerSupport")

        override fun install(pipeline: Application, configure: SwaggerUiConfiguration.() -> Unit): SwaggerSupport {
            val (path, forwardRoot, provideUi, swagger) = SwaggerUiConfiguration().apply(configure)
            val feature = SwaggerSupport(swagger)
            pipeline.routing {
                get("/$path") {
                    redirect(path)
                }
                val ui = if (provideUi) SwaggerUi() else null
                get("/$path/{fileName}") {
                    val filename = call.parameters["fileName"]
                    if (filename == "swagger.json") {
                        call.respond(swagger)
                    } else {
                        ui?.serve(filename, call)
                    }
                }
                if (forwardRoot) {
                    get("/") {
                        redirect(path)
                    }
                }
            }
            return feature
        }

        private suspend fun PipelineContext<Unit, ApplicationCall>.redirect(path: String) {
            call.respondRedirect("/$path/index.html?url=swagger.json")
        }
    }

    /**
     * The [HttpMethod] types that don't support having a HTTP body element.
     */
    private val methodForbidsBody = setOf(HttpMethod.Get, HttpMethod.Delete)

    inline fun <reified LOCATION : Any, reified ENTITY_TYPE : Any> Metadata.apply(method: HttpMethod) {
        apply(LOCATION::class, typeInfo<ENTITY_TYPE>(), method)
    }

    private fun Metadata.createBodyType(typeInfo: TypeInfo): BodyType =
        bodySchema?.let {
            BodyFromSchema(
                name = bodySchema.name ?: typeInfo.modelName(),
                schema = bodySchema.schema
            )
        } ?: BodyFromReflection(typeInfo)

    private fun Metadata.requireMethodSupportsBody(method: HttpMethod) =
        require(!(methodForbidsBody.contains(method) && bodySchema != null)) {
            "Method type $method does not support a body parameter."
        }

    fun Metadata.apply(locationClass: KClass<*>, bodyTypeInfo: TypeInfo, method: HttpMethod) {
        requireMethodSupportsBody(method)
        val bodyType = createBodyType(bodyTypeInfo)
        val clazz = locationClass.java
        val location = clazz.getAnnotation(Location::class.java)
        val tags = clazz.getAnnotation(Group::class.java)

        applyOperations(location, tags, method, locationClass, bodyType)
    }

    private fun <LOCATION : Any> Metadata.applyOperations(
        location: Location,
        group: Group?,
        method: HttpMethod,
        locationType: KClass<LOCATION>,
        bodyType: BodyType
    ) {

        when (bodyType) {
            is BodyFromSchema -> addDefintion(bodyType.name, bodyType.schema)
            is BodyFromReflection -> bodyType.typeInfo.let { typeInfo ->
                if (typeInfo.type != Unit::class) {
                    addDefinition(typeInfo)
                }
            }
        }

        fun createOperation(): Operation {
            val responses = responses.map { (status, type) ->
                val response = when (type) {
                    is ResponseFromReflection -> {
                        addDefinition(type.type)
                        Response.create(status, type.type)
                    }
                    is ResponseSchema -> {
                        addDefintion(type.name, type.schema)
                        Response.create(type.name)
                    }
                }

                status.value.toString() to response
            }.toMap()

            val parameters = mutableListOf<Parameter>().apply {
                if ((bodyType as? BodyFromReflection)?.typeInfo?.type != Unit::class) {
                    add(bodyType.bodyParameter())
                }
                addAll(locationType.memberProperties.map {
                    it.toParameter(location.path).let {
                        addDefinitions(it.second)
                        it.first
                    }
                })
                parameter?.let {
                    addAll(it.memberProperties.map {
                        it.toParameter(location.path, ParameterInputType.query).let {
                            addDefinitions(it.second)
                            it.first
                        }
                    })
                }
                headers?.let {
                    addAll(it.memberProperties.map {
                        it.toParameter(location.path, ParameterInputType.header).let {
                            addDefinitions(it.second)
                            it.first
                        }
                    })
                }
            }

            return Operation.create(
                this,
                responses,
                parameters,
                location,
                group,
                method,
                locationType
            )
        }

        swagger.paths
            .getOrPut(location.path) { mutableMapOf() }
            .put(
                method.value.toLowerCase(),
                createOperation()
            )
    }

    private fun addDefintion(name: String, schema: Any) {
        swagger.definitions.putIfAbsent(name, schema)
    }

    private fun addDefinition(typeInfo: TypeInfo) {
        if (typeInfo.type != Unit::class) {
            val accruedNewDefinitions = mutableListOf<TypeInfo>()
            swagger.definitions.computeIfAbsent(typeInfo.modelName()) {
                val modelWithAdditionalDefinitions = createModelData(typeInfo)
                accruedNewDefinitions.addAll(modelWithAdditionalDefinitions.second)
                modelWithAdditionalDefinitions.first
            }

            accruedNewDefinitions.forEach { addDefinition(it) }
        }
    }

    private fun addDefinitions(kClasses: Collection<TypeInfo>) =
        kClasses.forEach {
            addDefinition(it)
        }
}

data class SwaggerUiConfiguration(
    var path: String = "apidocs",
    var forwardRoot: Boolean = false,
    var provideUi: Boolean = true,
    var swagger: Swagger = Swagger()
)
