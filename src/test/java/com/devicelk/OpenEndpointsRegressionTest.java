package com.devicelk;

import com.devicelk.grpc.InventoryRequest;
import com.devicelk.grpc.InventoryResponse;
import com.devicelk.grpc.ProductGrpcServiceGrpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression cover for everything that was reachable <b>before</b> this
 * application had any security.
 * <p>
 * Adding Spring Security to the classpath authenticates every request and turns
 * on CSRF by default. Both would have been silent breakages: the admin portal's
 * writes to {@code /inventory/**} would start returning 403, and the AI
 * retrieval service's gRPC calls could stop resolving — neither visible from the
 * cart module's own tests, which is exactly why this class exists.
 * <p>
 * These are assertions about what stayed open, not about what should be open.
 * If the inventory API is locked down later, this file should change in the same
 * commit that does it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                // A real port: the gRPC surface is being called for real below.
                "grpc.server.port=9099"
        }
)
@AutoConfigureMockMvc
class OpenEndpointsRegressionTest extends AbstractPostgresTest {

    private static ManagedChannel channel;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterAll
    static void closeChannel() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("inventory REST reads stay open — no token, still 200")
    void inventoryReadsRemainOpen() throws Exception {
        mockMvc.perform(get("/inventory"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("inventory REST writes stay open — CSRF did not start rejecting them")
    void inventoryWritesRemainOpen() throws Exception {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "Regression Test Widget " + System.nanoTime());
        product.put("brand", "TestBrand");
        product.put("category", "ACCESSORIES");
        product.put("price", 1999.99);
        product.put("stockQuantity", 5);
        product.put("minStockThreshold", 1);
        product.put("description", "Created by OpenEndpointsRegressionTest");

        // Without the second filter chain's csrf().disable() this is a 403.
        mockMvc.perform(post("/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("actuator health stays open")
    void actuatorRemainsOpen() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the gRPC service still answers an unauthenticated call")
    void grpcRemainsOpen() throws Exception {
        // Seed a product through REST so there is a known id to ask gRPC about.
        Map<String, Object> product = new LinkedHashMap<>();
        String name = "gRPC Regression Widget " + System.nanoTime();
        product.put("name", name);
        product.put("brand", "TestBrand");
        product.put("category", "ACCESSORIES");
        product.put("price", 4500.00);
        product.put("stockQuantity", 7);
        product.put("minStockThreshold", 1);
        product.put("description", "Created by OpenEndpointsRegressionTest");

        String created = mockMvc.perform(post("/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long productId = objectMapper.readTree(created).get("id").asLong();

        channel = ManagedChannelBuilder.forAddress("localhost", 9099)
                .usePlaintext()
                .build();

        InventoryResponse response = ProductGrpcServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .getProductStockAndPrice(InventoryRequest.newBuilder()
                        .setProductId(productId)
                        .build());

        // No credentials were attached. If Spring Security had started guarding
        // the gRPC server, this call would fail UNAUTHENTICATED instead.
        assertThat(response.getProductId()).isEqualTo(productId);
        assertThat(response.getName()).isEqualTo(name);
        assertThat(response.getStockQuantity()).isEqualTo(7);
    }
}
