package com.devicelk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point of the DeviceLK commerce modular monolith.
 * <p>
 * Lives in the root {@code com.devicelk} package so that component scanning
 * (and Spring Modulith's module detection) covers every module beneath it:
 * {@code inventory}, {@code cart} and {@code order}. Each direct sub-package of
 * {@code com.devicelk} is treated as an application module.
 * <p>
 * <b>{@code @EnableAsync} is here for Spring Modulith's event listeners.</b>
 * {@code @ApplicationModuleListener} is meta-annotated {@code @Async}, but that
 * annotation does nothing at all unless async processing is switched on — and
 * the failure mode is silent: the listener simply runs inline on the publishing
 * thread and everything still appears to work. What is lost is the isolation
 * that makes event-driven handlers safe to add. Without this, a slow
 * confirmation email would extend the customer's checkout response, and
 * event handling would share the caller's thread rather than being genuinely
 * decoupled from it. The listeners are written on the assumption that they are
 * asynchronous; this is what makes that assumption true.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableAsync
public class DeviceLkCommerceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceLkCommerceApplication.class, args);
    }
}
