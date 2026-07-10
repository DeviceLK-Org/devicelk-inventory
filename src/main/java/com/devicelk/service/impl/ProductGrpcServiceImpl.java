package com.devicelk.service.impl;

import com.devicelk.domain.Product;
import com.devicelk.grpc.BulkInventoryRequest;
import com.devicelk.grpc.BulkInventoryResponse;
import com.devicelk.grpc.InventoryRequest;
import com.devicelk.grpc.InventoryResponse;
import com.devicelk.grpc.ProductGrpcServiceGrpc;
import com.devicelk.repository.ProductRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * gRPC service implementation for dynamic inventory queries.
 * Located properly within the core service implementation package.
 */
@GrpcService
@RequiredArgsConstructor
public class ProductGrpcServiceImpl extends ProductGrpcServiceGrpc.ProductGrpcServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ProductGrpcServiceImpl.class);

    private final ProductRepository productRepository;



    @Override
    public void getProductStockAndPrice(InventoryRequest request, StreamObserver<InventoryResponse> responseObserver) {
        long productId = request.getProductId();

        try {
            // Query the PostgreSQL database via JpaRepository
            Optional<Product> productOptional = productRepository.findById(productId);

            if (productOptional.isEmpty()) {
                // Return standard gRPC NOT_FOUND error if product doesn't exist in database
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Product with ID " + productId + " was not found in the DeviceLK inventory.")
                        .asRuntimeException());
                return;
            }

            // Stream the response back to the client and close the channel
            responseObserver.onNext(toInventoryResponse(productOptional.get()));
            responseObserver.onCompleted();
        } catch (Throwable t) {
            // Throwable, not Exception: linkage errors from stale generated stubs
            // (the proto recently gained fields) must surface here too.
            log.error("getProductStockAndPrice failed for productId {}", productId, t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("getProductStockAndPrice failed for productId " + productId + ": " + t)
                    .asRuntimeException());
        }
    }

    @Override
    public void getBulkProductStockAndPrice(BulkInventoryRequest request,
                                            StreamObserver<BulkInventoryResponse> responseObserver) {
        try {
            List<Long> productIds = request.getProductIdsList();

            // Single round-trip lookup: any missing ids are silently absent from
            // the result list — the AI retrieval layer handles partial matches.
            List<Product> products = productRepository.findByIdIn(productIds);

            BulkInventoryResponse.Builder responseBuilder = BulkInventoryResponse.newBuilder();
            for (Product product : products) {
                responseBuilder.addResponses(toInventoryResponse(product));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to resolve bulk inventory request for {} product id(s)",
                    request.getProductIdsCount(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to resolve bulk inventory request: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /** Builds the production-ready Protobuf binary response for a single product. */
    private InventoryResponse toInventoryResponse(Product product) {
        return InventoryResponse.newBuilder()
                .setProductId(product.getId())
                .setPrice(product.getPrice().toPlainString()) // toPlainString() avoids scientific notation
                .setStockQuantity(product.getStockQuantity())
                .setIsAvailable(product.getStockQuantity() > 0)
                .setName(product.getName())
                // Protobuf setters reject null — the description column is nullable
                .setDescription(product.getDescription() == null ? "" : product.getDescription())
                .build();
    }
}
