/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.protocols.parse

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.client.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.client.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.client.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.client.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.client.testutil.renderWithModelBuilder
import software.amazon.smithy.rust.codegen.client.testutil.testCodegenContext
import software.amazon.smithy.rust.codegen.client.testutil.testSymbolProvider
import software.amazon.smithy.rust.codegen.client.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.generators.EnumGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.UnionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.protocols.parse.XmlBindingTraitParserGenerator
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.smithy.transformers.RecursiveShapeBoxer
import software.amazon.smithy.rust.codegen.core.util.expectTrait
import software.amazon.smithy.rust.codegen.core.util.lookup
import software.amazon.smithy.rust.codegen.core.util.outputShape

internal class XmlBindingTraitParserGeneratorTest {
    private val baseModel = """
        namespace test
        use aws.protocols#restXml
        union Choice {
            @xmlFlattened
            @xmlName("Hi")
            flatMap: MyMap,

            deepMap: MyMap,

            @xmlFlattened
            flatList: SomeList,

            deepList: SomeList,

            s: String,

            enum: FooEnum,

            date: Timestamp,

            number: Double,

            top: Top,

            blob: Blob
        }

        @enum([{name: "FOO", value: "FOO"}])
        string FooEnum

        map MyMap {
            @xmlName("Name")
            key: String,

            @xmlName("Setting")
            value: Choice,
        }

        list SomeList {
            member: Choice
        }

        structure Top {
            choice: Choice,

            @xmlAttribute
            extra: Long,

            @xmlName("prefix:local")
            renamedWithPrefix: String
        }

        @http(uri: "/top", method: "POST")
        operation Op {
            input: Top,
            output: Top
        }
    """.asSmithyModel()

    @Test
    fun `generates valid parsers`() {
        val model = RecursiveShapeBoxer.transform(OperationNormalizer.transform(baseModel))
        val symbolProvider = testSymbolProvider(model)
        val parserGenerator = XmlBindingTraitParserGenerator(
            testCodegenContext(model),
            RuntimeType.wrappedXmlErrors(TestRuntimeConfig),
        ) { _, inner -> inner("decoder") }
        val operationParser = parserGenerator.operationParser(model.lookup("test#Op"))!!
        val project = TestWorkspace.testProject(testSymbolProvider(model))
        project.lib { writer ->
            writer.unitTest(
                name = "valid_input",
                test = """
                    let xml = br#"<Top>
                        <choice>
                            <Hi>
                                <Name>some key</Name>
                                <Setting>
                                    <s>hello</s>
                                </Setting>
                            </Hi>
                        </choice>
                        <prefix:local>hey</prefix:local>
                    </Top>
                    "#;
                    let output = ${writer.format(operationParser)}(xml, output::op_output::Builder::default()).unwrap().build();
                    let mut map = std::collections::HashMap::new();
                    map.insert("some key".to_string(), model::Choice::S("hello".to_string()));
                    assert_eq!(output.choice, Some(model::Choice::FlatMap(map)));
                    assert_eq!(output.renamed_with_prefix.as_deref(), Some("hey"));
                """,
            )

            writer.unitTest(
                name = "ignore_extras",
                test = """
                    let xml = br#"<Top>
                        <notchoice>
                            <extra/>
                            <stuff/>
                            <noone/>
                            <needs>5</needs>
                        </notchoice>
                        <choice>
                            <Hi>
                                <Name>some key</Name>
                                <Setting>
                                    <s>hello</s>
                                </Setting>
                            </Hi>
                        </choice>
                    </Top>
                    "#;
                    let output = ${writer.format(operationParser)}(xml, output::op_output::Builder::default()).unwrap().build();
                    let mut map = std::collections::HashMap::new();
                    map.insert("some key".to_string(), model::Choice::S("hello".to_string()));
                    assert_eq!(output.choice, Some(model::Choice::FlatMap(map)));
                """,
            )

            writer.unitTest(
                name = "nopanics_on_invalid",
                test = """
                    let xml = br#"<Top>
                        <notchoice>
                            <extra/>
                            <stuff/>
                            <noone/>
                            <needs>5</needs>
                        </notchoice>
                        <choice>
                            <Hey>
                                <Name>some key</Name>
                                <Setting>
                                    <s>hello</s>
                                </Setting>
                            </Hey>
                        </choice>
                    </Top>
                    "#;
                    ${writer.format(operationParser)}(xml, output::op_output::Builder::default()).expect("unknown union variant does not cause failure");
                """,
            )
            writer.unitTest(
                name = "unknown_union_variant",
                test = """
                    let xml = br#"<Top>
                        <choice>
                            <NewVariantName>
                                <Name>some key</Name>
                                <Setting>
                                    <s>hello</s>
                                </Setting>
                            </NewVariantName>
                        </choice>
                    </Top>
                    "#;
                    let output = ${writer.format(operationParser)}(xml, output::op_output::Builder::default()).unwrap().build();
                    assert!(output.choice.unwrap().is_unknown());
                """,
            )
        }
        project.withModule(RustModule.public("model")) {
            model.lookup<StructureShape>("test#Top").renderWithModelBuilder(model, symbolProvider, it)
            UnionGenerator(model, symbolProvider, it, model.lookup("test#Choice")).render()
            val enum = model.lookup<StringShape>("test#FooEnum")
            EnumGenerator(model, symbolProvider, it, enum, enum.expectTrait()).render()
        }

        project.withModule(RustModule.public("output")) {
            model.lookup<OperationShape>("test#Op").outputShape(model).renderWithModelBuilder(model, symbolProvider, it)
        }
        project.compileAndTest()
    }
}