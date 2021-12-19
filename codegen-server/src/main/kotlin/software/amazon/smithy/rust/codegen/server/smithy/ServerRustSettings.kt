/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rust.codegen.server.smithy

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.smithy.CODEGEN_SETTINGS
import software.amazon.smithy.rust.codegen.smithy.CodegenConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RustSettings
import java.util.Optional
import java.util.logging.Logger

/**
 * Configuration of codegen settings
 *
 * [renameExceptions]: Rename `Exception` to `Error` in the generated SDK
 * [includeFluentClient]: Generate a `client` module in the generated SDK (currently the AWS SDK sets this to false
 *   and generates its own client)
 *
 * [addMessageToErrors]: Adds a `message` field automatically to all error shapes
 * [formatTimeoutSeconds]: Timeout for running cargo fmt at the end of code generation
 */
data class ServerCodegenConfig(
    val renameExceptions: Boolean = true,
    val includeFluentClient: Boolean = false,
    val addMessageToErrors: Boolean = true,
    val formatTimeoutSeconds: Int = 20,
    // TODO(EventStream): [CLEANUP] Remove this property when turning on Event Stream for all services
    val eventStreamAllowList: Set<String> = emptySet(),
) {
    companion object {
        fun fromNode(node: Optional<ObjectNode>): CodegenConfig {
            return if (node.isPresent) {
                CodegenConfig.fromNode(node)
            } else {
                CodegenConfig(
                    true,
                    false,
                    true,
                    20,
                    emptySet()
                )
            }
        }
    }
}

/**
 * Settings used by [RustCodegenPlugin]
 */
class ServerRustSettings(
    val service: ShapeId,
    val moduleName: String,
    val moduleVersion: String,
    val moduleAuthors: List<String>,
    val moduleDescription: String?,
    val moduleRepository: String?,
    val runtimeConfig: RuntimeConfig,
    val codegenConfig: CodegenConfig,
    val license: String?,
    val examplesUri: String? = null,
    private val model: Model
) {

    /**
     * Get the corresponding [ServiceShape] from a model.
     * @return Returns the found `Service`
     * @throws CodegenException if the service is invalid or not found
     */
    fun getService(model: Model): ServiceShape {
        return model
            .getShape(service)
            .orElseThrow { CodegenException("Service shape not found: $service") }
            .asServiceShape()
            .orElseThrow { CodegenException("Shape is not a service: $service") }
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(ServerRustSettings::class.java.name)

        /**
         * Create settings from a configuration object node.
         *
         * @param model Model to infer the service from (if not explicitly set in config)
         * @param config Config object to load
         * @throws software.amazon.smithy.model.node.ExpectationNotMetException
         * @return Returns the extracted settings
         */
        fun from(model: Model, config: ObjectNode): RustSettings {
            val codegenSettings = config.getObjectMember(CODEGEN_SETTINGS)
            val codegenConfig = ServerCodegenConfig.fromNode(codegenSettings)
            return RustSettings.fromCodegenConfig(model, config, codegenConfig)
        }
    }
}
