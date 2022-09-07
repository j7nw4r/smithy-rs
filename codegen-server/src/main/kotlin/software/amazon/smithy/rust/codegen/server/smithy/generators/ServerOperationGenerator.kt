/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.documentShape
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.smithy.CoreCodegenContext
import software.amazon.smithy.rust.codegen.util.toPascalCase

class ServerOperationGenerator(
    coreCodegenContext: CoreCodegenContext,
    private val operation: OperationShape,
) {
    private val runtimeConfig = coreCodegenContext.runtimeConfig
    private val codegenScope =
        arrayOf(
            "SmithyHttpServer" to
                ServerCargoDependency.SmithyHttpServer(runtimeConfig).asType(),
        )
    private val symbolProvider = coreCodegenContext.symbolProvider
    private val model = coreCodegenContext.model

    private val operationName = symbolProvider.toSymbol(operation).name.toPascalCase()
    private val operationId = operation.id

    /** Returns `std::convert::Infallible` if the model provides no errors. */
    private fun operationError(): Writable = writable {
        if (operation.errors.isEmpty()) {
            rust("std::convert::Infallible")
        } else {
            rust("crate::error::${operationName}Error")
        }
    }

    fun render(writer: RustWriter) {
        documentShape(operation, model)

        rustTemplate(
            """
            pub struct $operationName;

            impl #{SmithyHttpServer}::operation::OperationShape for $operationName {
                const NAME: &'static str = "${operationId.toString().replace("#", "##")}";

                type Input = crate::input::${operationName}Input;
                type Output = crate::output::${operationName}Output;
                type Error = #{Error:W};
            }
            """,
            "Error" to operationError(),
            *codegenScope,
        )
        // Adds newline to end of render
        writer.rust("")
    }
}