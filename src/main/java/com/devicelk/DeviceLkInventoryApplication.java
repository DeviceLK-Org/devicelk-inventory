package com.devicelk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point of the DeviceLK inventory service.
 * <p>
 * Owns the product catalogue and stock, and nothing else. It serves a REST API on
 * 8081 and a gRPC API on 9090; the gRPC surface is the one other services use —
 * DeviceLK-AIRetrieval for catalogue reads, DeviceLK-Commerce for reads and for
 * the stock reservation lifecycle.
 * <p>
 * The Spring Modulith machinery is kept for one narrow purpose: {@code
 * ModularityTests} verifies that the {@code inventory} module does not reach into
 * the generated {@code grpc} stubs' internals, and would notice if a second
 * module appeared here.
 * <p>
 * There is deliberately no {@code @EnableAsync}: this service publishes no
 * application events, so enabling it would suggest an asynchronous path that does
 * not exist.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class DeviceLkInventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceLkInventoryApplication.class, args);
    }
}
