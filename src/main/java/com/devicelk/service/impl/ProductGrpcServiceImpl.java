package com.devicelk.service.impl;

import com.devicelk.domain.Product;
import com.devicelk.grpc.InventoryRequest;
import com.devicelk.grpc.InventoryResponse;
import com.devicelk.grpc.ProductGrpcServiceGrpc;
import com.devicelk.repository.ProductRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.Optional;

/**
 * gRPC service implementation for dynamic inventory queries.
 * Located properly within the core service implementation package.
 */
@GrpcService
@RequiredArgsConstructor
public class ProductGrpcServiceImpl extends ProductGrpcServiceGrpc.ProductGrpcServiceImplBase {

    private final ProductRepository productRepository;



    @Override
    public void getProductStockAndPrice(InventoryRequest request, StreamObserver<InventoryResponse> responseObserver) {
        long productId = request.getProductId();

        // Query the PostgreSQL database via JpaRepository
        Optional<Product> productOptional = productRepository.findById(productId);

        if (productOptional.isEmpty()) {
            // Return standard gRPC NOT_FOUND error if product doesn't exist in database
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Product with ID " + productId + " was not found in the DeviceLK inventory.")
                    .asRuntimeException());
            return;
        }

        Product product = productOptional.get();
        boolean isAvailable = product.getStockQuantity() > 0;

        // Build the production-ready Protobuf binary response
        InventoryResponse response = InventoryResponse.newBuilder()
                .setProductId(product.getId())
                .setPrice(product.getPrice().toPlainString()) // toPlainString() avoids scientific notation
                .setStockQuantity(product.getStockQuantity())
                .setIsAvailable(isAvailable)
                .build();

        // Stream the response back to the client and close the channel
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}