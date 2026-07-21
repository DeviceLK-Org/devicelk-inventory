package com.devicelk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point of the DeviceLK commerce modular monolith.
 * <p>
 * Lives in the root {@code com.devicelk} package so that component scanning
 * (and Spring Modulith's module detection) covers every module beneath it:
 * {@code inventory}, and later {@code cart} and {@code order}. Each direct
 * sub-package of {@code com.devicelk} is treated as an application module.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class DeviceLkCommerceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceLkCommerceApplication.class, args);
    }
}
