/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
use pokemon_service_client_usage::setup_tracing_subscriber;
/// This example demonstrates how to create a Smithy Client and set connection
/// and operation related timeouts on the client.
///
/// The example assumes that the Pokemon service is running on the localhost on TCP port 13734.
/// Refer to the [README.md](https://github.com/awslabs/smithy-rs/tree/main/examples/pokemon-service-client-usage/README.md)
/// file for instructions on how to launch the service locally.
///
/// The example can be run using `cargo run --example timeout-config`
///
use std::time::Duration;
use tracing::info;

use pokemon_service_client::Client as PokemonClient;

static BASE_URL: &str = "http://localhost:13734";

/// Creates a new Smithy client that is configured to communicate with a locally running Pokemon service on TCP port 13734.
///
/// # Examples
///
/// Basic usage:
///
/// ```
/// let client = create_client();
/// ```
fn create_client() -> PokemonClient {
    // Different type of timeouts can be set on the client. These are:
    // operation_attempt_timeout - If retries are enabled, this represents the timeout
    //    for each individual operation attempt.
    // operation_timeout - Overall timeout for the operation to complete.
    // connect timeout - The amount of time allowed for a connection to be established.
    let timeout_config = aws_smithy_types::timeout::TimeoutConfig::builder()
        .operation_attempt_timeout(Duration::from_secs(1))
        .operation_timeout(Duration::from_secs(5))
        .connect_timeout(Duration::from_millis(500))
        .build();

    let config = pokemon_service_client::Config::builder()
        .endpoint_resolver(BASE_URL)
        .timeout_config(timeout_config)
        .sleep_impl(::aws_smithy_async::rt::sleep::SharedAsyncSleep::new(
            aws_smithy_async::rt::sleep::default_async_sleep()
                .expect("sleep implementation could not be created"),
        ))
        .build();

    // Apply the configuration on the Client, and return that.
    PokemonClient::from_conf(config)
}

#[tokio::main]
async fn main() {
    setup_tracing_subscriber();

    // Create a configured Smithy client.
    let client = create_client();

    // Call an operation `get_server_statistics` on Pokemon service.
    let response = client
        .get_server_statistics()
        .send()
        .await
        .expect("Pokemon service does not seem to be running on localhost:13734");

    info!(%BASE_URL, ?response, "Response received");
}
