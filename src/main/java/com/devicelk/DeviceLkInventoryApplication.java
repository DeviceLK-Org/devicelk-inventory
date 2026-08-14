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
 * <b>This application briefly hosted the cart and order modules as a Spring
 * Modulith monolith.</b> They were extracted into DeviceLK-Commerce, and the
 * boundary they were written against — a facade, referenced by id rather than by
 * foreign key — is what made that extraction a change of transport rather than a
 * rewrite. The Modulith machinery is kept for one narrow purpose: {@code
 * ModularityTests} still verifies that the {@code inventory} module does not reach
 * into the generated {@code grpc} stubs' internals, and it is the thing that would
 * notice if a second module quietly appeared here again.
 * <p>
 * <b>{@code @EnableAsync} was removed with the order module.</b> It existed for
 * {@code @ApplicationModuleListener}, which is meta-annotated {@code @Async} and
 * silently degrades to running inline without it. This service publishes no
 * application events — {@code OrderPlacedEvent} went to DeviceLK-Commerce along
 * with the event-publication registry — so there is nothing left for it to enable,
 * and leaving it would suggest an asynchronous path that does not exist.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class DeviceLkInventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceLkInventoryApplication.class, args);
    }
}
