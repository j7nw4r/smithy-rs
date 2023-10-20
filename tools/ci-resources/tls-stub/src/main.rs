/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::env;
use std::fs::File;
use std::io::BufReader;
use std::time::Duration;

use aws_config::timeout::TimeoutConfig;
use aws_credential_types::Credentials;
use aws_sdk_sts::error::SdkError;
use aws_smithy_runtime::client::http::hyper_014::HyperClientBuilder;

#[cfg(debug_assertions)]
use x509_parser::prelude::*;

const OPERATION_TIMEOUT: u64 = 5;

fn unsupported() {
    println!("UNSUPPORTED");
    std::process::exit(exitcode::OK);
}

fn get_credentials() -> Credentials {
    Credentials::from_keys(
        "AKIAIOSFODNN7EXAMPLE",
        "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        None,
    )
}

#[cfg(debug_assertions)]
fn debug_cert(cert: &[u8]) {
    let x509 = X509Certificate::from_der(cert).unwrap();
    let subject = x509.1.subject();
    let serial = x509.1.raw_serial_as_string();
    println!("Adding root CA: {subject} ({serial})");
}

fn add_cert_to_store(cert: &[u8], store: &mut rustls::RootCertStore) {
    let cert = rustls::Certificate(cert.to_vec());
    #[cfg(debug_assertions)]
    debug_cert(&cert.0);
    if let Err(e) = store.add(&cert) {
        println!("Error adding root certificate: {e}");
        unsupported();
    }
}

fn load_ca_bundle(filename: &String, roots: &mut rustls::RootCertStore) {
    match File::open(filename) {
        Ok(f) => {
            let mut f = BufReader::new(f);
            match rustls_pemfile::certs(&mut f) {
                Ok(certs) => {
                    for cert in certs {
                        add_cert_to_store(&cert, roots);
                    }
                }
                Err(e) => {
                    println!("Error reading PEM file: {e}");
                    unsupported();
                }
            }
        }
        Err(e) => {
            println!("Error opening file '{filename}': {e}");
            unsupported();
        }
    }
}

fn load_native_certs(roots: &mut rustls::RootCertStore) {
    let certs = rustls_native_certs::load_native_certs();
    if let Err(ref e) = certs {
        println!("Error reading native certificates: {e}");
        unsupported();
    }
    for cert in certs.unwrap() {
        add_cert_to_store(&cert.0, roots);
    }
    let mut pem_ca_cert = b"\
-----BEGIN CERTIFICATE-----
-----END CERTIFICATE-----\
" as &[u8];
    let certs = rustls_pemfile::certs(&mut pem_ca_cert).unwrap();
    for cert in certs {
        add_cert_to_store(&cert, roots);
    }
}

async fn create_client(
    roots: rustls::RootCertStore,
    host: &String,
    port: &String,
) -> aws_sdk_sts::Client {
    let credentials = get_credentials();
    let tls_config = rustls::client::ClientConfig::builder()
        .with_safe_default_cipher_suites()
        .with_safe_default_kx_groups()
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_root_certificates(roots)
        .with_no_client_auth();
    let tls_connector = hyper_rustls::HttpsConnectorBuilder::new()
        .with_tls_config(tls_config)
        .https_only()
        .enable_http1()
        .enable_http2()
        .build();
    let http_client = HyperClientBuilder::new().build(tls_connector);
    let sdk_config = aws_config::from_env()
        .http_client(http_client)
        .credentials_provider(credentials)
        .region("us-nether-1")
        .endpoint_url(format!("https://{host}:{port}"))
        .timeout_config(
            TimeoutConfig::builder()
                .operation_timeout(Duration::from_secs(OPERATION_TIMEOUT))
                .build(),
        )
        .load()
        .await;
    aws_sdk_sts::Client::new(&sdk_config)
}

#[tokio::main]
async fn main() -> Result<(), aws_sdk_sts::Error> {
    let argv: Vec<String> = env::args().collect();
    if argv.len() < 3 || argv.len() > 4 {
        eprintln!("Syntax: {} <hostname> <port> [ca-file]", argv[0]);
        std::process::exit(exitcode::USAGE);
    }
    let mut roots = rustls::RootCertStore::empty();
    if argv.len() == 4 {
        print!(
            "Connecting to https://{}:{} with root CA bundle from {}: ",
            &argv[1], &argv[2], &argv[3]
        );
        load_ca_bundle(&argv[3], &mut roots);
    } else {
        print!(
            "Connecting to https://{}:{} with native roots: ",
            &argv[1], &argv[2]
        );
        load_native_certs(&mut roots);
    }
    let sts_client = create_client(roots, &argv[1], &argv[2]).await;
    match sts_client.get_caller_identity().send().await {
        Ok(_) => println!("\nACCEPT"),
        Err(SdkError::DispatchFailure(e)) => println!("{e:?}\nREJECT"),
        Err(SdkError::ServiceError(e)) => println!("{e:?}\nACCEPT"),
        Err(e) => {
            println!("Unexpected error: {e:#?}");
            std::process::exit(exitcode::SOFTWARE);
        }
    }
    Ok(())
}
