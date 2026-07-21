package com.devicelk.inventory.api;

import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Product;
import com.devicelk.grpc.BulkInventoryRequest;
import com.devicelk.grpc.BulkInventoryResponse;
import com.devicelk.grpc.InventoryRequest;
import com.devicelk.grpc.InventoryResponse;
import com.devicelk.grpc.ProductGrpcServiceGrpc;
import com.devicelk.grpc.ProductSearchRequest;
import com.devicelk.grpc.ProductSearchResponse;
import com.devicelk.inventory.repository.ProductRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * gRPC service implementation for dynamic inventory queries.
 * <p>
 * Lives in the {@code api} package because — like the REST controller — it is
 * an inbound transport adapter of the inventory module (the AI retrieval
 * service is its client). Package-private: nothing outside this package refers
 * to it; the gRPC runtime discovers it via {@link GrpcService} scanning.
 */
@GrpcService
@RequiredArgsConstructor
class ProductGrpcServiceImpl extends ProductGrpcServiceGrpc.ProductGrpcServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ProductGrpcServiceImpl.class);

    /** Result size used when the client sends limit = 0 (proto3 "unset"). */
    private static final int DEFAULT_SEARCH_LIMIT = 10;

    /** Hard cap so a bad request can never pull the whole products table. */
    private static final int MAX_SEARCH_LIMIT = 50;

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

    @Override
    public void searchProducts(ProductSearchRequest request,
                               StreamObserver<ProductSearchResponse> responseObserver) {
        // Validate before touching the database: a malformed request must map to
        // INVALID_ARGUMENT, never INTERNAL.
        Category category;
        BigDecimal minPrice;
        BigDecimal maxPrice;
        try {
            category = parseCategory(request.getCategory());
            minPrice = parsePrice(request.getMinPrice(), "min_price");
            maxPrice = parsePrice(request.getMaxPrice(), "max_price");
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
            return;
        }

        int limit = request.getLimit() <= 0
                ? DEFAULT_SEARCH_LIMIT
                : Math.min(request.getLimit(), MAX_SEARCH_LIMIT);

        try {
            // Cheapest-first with an id tiebreak keeps the truncated result set
            // deterministic across calls.
            List<Product> products = productRepository.findAll(
                    ProductRepository.searchSpecification(
                            category, minPrice, maxPrice, request.getInStockOnly()),
                    PageRequest.of(0, limit, Sort.by("price").ascending().and(Sort.by("id").ascending()))
            ).getContent();

            ProductSearchResponse.Builder responseBuilder = ProductSearchResponse.newBuilder();
            for (Product product : products) {
                responseBuilder.addProducts(toInventoryResponse(product));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Throwable t) {
            log.error("searchProducts failed (category='{}', min_price='{}', max_price='{}', "
                            + "in_stock_only={}, limit={})",
                    request.getCategory(), request.getMinPrice(), request.getMaxPrice(),
                    request.getInStockOnly(), request.getLimit(), t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("searchProducts failed: " + t)
                    .asRuntimeException());
        }
    }

    /**
     * Resolves the category filter; empty means "any" (returns {@code null}).
     *
     * @throws IllegalArgumentException when the value is not a known {@link Category}
     */
    private static Category parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return Category.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown category '" + category + "'. Valid categories: "
                            + List.of(Category.values()));
        }
    }

    /**
     * Parses an optional price bound; empty means "no bound" (returns {@code null}).
     *
     * @throws IllegalArgumentException when the value is not a valid decimal number
     */
    private static BigDecimal parsePrice(String price, String fieldName) {
        if (price == null || price.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(price.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid " + fieldName + " '" + price + "': not a valid decimal number.");
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
