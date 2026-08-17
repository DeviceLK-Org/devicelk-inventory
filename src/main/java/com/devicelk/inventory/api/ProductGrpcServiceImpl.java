package com.devicelk.inventory.api;

import com.devicelk.inventory.InsufficientStockException;
import com.devicelk.inventory.ReservationLine;
import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Money;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.domain.Stock;
import com.devicelk.inventory.repository.StockRepository;
import com.devicelk.inventory.service.StockReservationService;
import com.devicelk.grpc.BulkInventoryRequest;
import com.devicelk.grpc.BulkInventoryResponse;
import com.devicelk.grpc.InventoryRequest;
import com.devicelk.grpc.InventoryResponse;
import com.devicelk.grpc.ProductGrpcServiceGrpc;
import com.devicelk.grpc.ProductSearchRequest;
import com.devicelk.grpc.ProductSearchResponse;
import com.devicelk.grpc.ReservationFailure;
import com.devicelk.grpc.ReservationRequest;
import com.devicelk.grpc.ReservationResponse;
import com.devicelk.inventory.repository.ProductRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private final StockRepository stockRepository;

    /**
     * The reservation lifecycle. Reached through the service rather than
     * {@link StockRepository} directly, so the invariants — all-or-nothing across
     * lines, totals summed per product, rows locked in a fixed order — stay in one
     * place and are shared by every inbound adapter.
     */
    private final StockReservationService stockReservationService;


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
            responseObserver.onNext(toInventoryResponse(
                    productOptional.get(), stockRepository.findById(productId).orElse(null)));
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
            Map<Long, Stock> stockByProductId = stockByProductId(products);

            BulkInventoryResponse.Builder responseBuilder = BulkInventoryResponse.newBuilder();
            for (Product product : products) {
                responseBuilder.addResponses(
                        toInventoryResponse(product, stockByProductId.get(product.getId())));
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
        Long minPriceCents;
        Long maxPriceCents;
        try {
            category = parseCategory(request.getCategory());
            minPriceCents = parsePriceCents(request.getMinPrice(), "min_price");
            maxPriceCents = parsePriceCents(request.getMaxPrice(), "max_price");
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
                            category, minPriceCents, maxPriceCents, request.getInStockOnly()),
                    PageRequest.of(0, limit,
                            Sort.by("priceCents").ascending().and(Sort.by("id").ascending()))
            ).getContent();
            Map<Long, Stock> stockByProductId = stockByProductId(products);

            ProductSearchResponse.Builder responseBuilder = ProductSearchResponse.newBuilder();
            for (Product product : products) {
                responseBuilder.addProducts(
                        toInventoryResponse(product, stockByProductId.get(product.getId())));
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

    // --- Reservation lifecycle ---------------------------------------------------
    //
    // The write half of this service. Each RPC is its own transaction, since
    // StockReservationServiceImpl's methods are @Transactional and there is no
    // ambient transaction to join. So a reservation that returns success has
    // COMMITTED and the caller's later failure cannot roll it back — compensating
    // is the caller's job, via ReleaseStock.

    @Override
    public void reserveStock(ReservationRequest request,
                             StreamObserver<ReservationResponse> responseObserver) {
        applyMovement(request, responseObserver, stockReservationService::reserve, "reserveStock");
    }

    @Override
    public void releaseStock(ReservationRequest request,
                             StreamObserver<ReservationResponse> responseObserver) {
        applyMovement(request, responseObserver, stockReservationService::release, "releaseStock");
    }

    @Override
    public void confirmReservation(ReservationRequest request,
                                   StreamObserver<ReservationResponse> responseObserver) {
        applyMovement(request, responseObserver, stockReservationService::confirm, "confirmReservation");
    }

    /**
     * Shared body of the three reservation RPCs; they differ only in which way
     * units move, expressed by the method reference passed in.
     * <p>
     * The failure mapping is the substance here, because each outcome tells the
     * caller to do something different:
     * <ul>
     *   <li>Insufficient stock → a successful RPC with {@code success=false}. The
     *       request was fine and the answer is simply no, with structured detail
     *       the caller acts on.</li>
     *   <li>Malformed line → {@code INVALID_ARGUMENT}, raised by
     *       {@link ReservationLine}'s constructor before anything is attempted, so
     *       a negative quantity cannot reach the service and <i>increase</i> stock.</li>
     *   <li>Releasing or confirming more than is held → {@code FAILED_PRECONDITION}.
     *       A caller bug, not a shortage; reporting a shortage would send them
     *       looking for a restock that cannot help.</li>
     *   <li>Optimistic-lock collision → {@code ABORTED}, which gRPC defines as
     *       retryable. INTERNAL would tell the caller the server is broken and to
     *       stop trying, when repeating the request may well succeed.</li>
     * </ul>
     */
    private void applyMovement(ReservationRequest request,
                               StreamObserver<ReservationResponse> responseObserver,
                               Consumer<List<ReservationLine>> movement,
                               String rpcName) {
        List<ReservationLine> lines;
        try {
            lines = toReservationLines(request);
        } catch (IllegalArgumentException e) {
            log.warn("{} rejected a malformed request: {}", rpcName, e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
            return;
        }

        try {
            movement.accept(lines);
            responseObserver.onNext(ReservationResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
            log.debug("{} applied {} line(s)", rpcName, lines.size());
        } catch (InsufficientStockException e) {
            log.info("{} refused: product {} requested {}, available {}",
                    rpcName, e.getProductId(), e.getRequested(), e.getAvailable());
            responseObserver.onNext(ReservationResponse.newBuilder()
                    .setSuccess(false)
                    .setFailure(ReservationFailure.newBuilder()
                            .setProductId(e.getProductId())
                            .setRequested(e.getRequested())
                            .setAvailable(e.getAvailable())
                            .build())
                    .build());
            responseObserver.onCompleted();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("{} lost an optimistic-lock race on {} line(s)", rpcName, lines.size());
            responseObserver.onError(Status.ABORTED
                    .withDescription("Concurrent stock update; retry the operation.")
                    .asRuntimeException());
        } catch (IllegalStateException e) {
            log.warn("{} rejected: {}", rpcName, e.getMessage());
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Throwable t) {
            // Throwable, not Exception: linkage errors from stale generated stubs
            // must surface here too, matching the read RPCs above.
            log.error("{} failed for {} line(s)", rpcName, lines.size(), t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(rpcName + " failed: " + t)
                    .asRuntimeException());
        }
    }

    /**
     * Converts the wire lines into the domain's value type.
     * <p>
     * Adds no checks of its own: {@link ReservationLine}'s constructor validates
     * quantity, and a second copy of that rule here would drift out of step.
     *
     * @throws IllegalArgumentException if any line has a non-positive quantity
     */
    private static List<ReservationLine> toReservationLines(ReservationRequest request) {
        return request.getLinesList().stream()
                .map(line -> new ReservationLine(line.getProductId(), line.getQuantity()))
                .toList();
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
     * Parses an optional price bound into minor units; empty means "no bound"
     * (returns {@code null}).
     * <p>
     * Converting here rather than at the query keeps every failure inside the
     * caller's INVALID_ARGUMENT handler, so a malformed bound never escapes as
     * INTERNAL.
     *
     * @throws IllegalArgumentException when the value is not a valid decimal
     *         number, or carries a fraction of a cent
     */
    private static Long parsePriceCents(String price, String fieldName) {
        if (price == null || price.isBlank()) {
            return null;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(price.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid " + fieldName + " '" + price + "': not a valid decimal number.");
        }
        try {
            return Money.toCents(parsed);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Invalid " + fieldName + " '" + price + "': more than 2 decimal places.");
        }
    }

    /** Indexes the stock rows for the given products by product id, in one query. */
    private Map<Long, Stock> stockByProductId(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        return stockRepository.findByProductIdIn(ids).stream()
                .collect(Collectors.toMap(Stock::getProductId, Function.identity()));
    }

    /**
     * Builds the production-ready Protobuf binary response for a single product.
     *
     * @param stock the product's stock row, or {@code null} if it has none —
     *              reported as zero stock and unavailable, which is the safe
     *              answer for a caller deciding whether it can sell the item
     */
    private InventoryResponse toInventoryResponse(Product product, Stock stock) {
        int availableQty = stock == null ? 0 : stock.getAvailableQty();
        if (stock == null) {
            log.warn("Stock record missing for product ID: {}; reporting zero stock", product.getId());
        }
        return InventoryResponse.newBuilder()
                .setProductId(product.getId())
                // Money.toDisplayString is the single cents -> decimal string
                // conversion; it never emits scientific notation.
                .setPrice(Money.toDisplayString(product.getPriceCents()))
                .setStockQuantity(availableQty)
                .setIsAvailable(availableQty > 0)
                .setName(product.getName())
                // Protobuf setters reject null — the description column is nullable
                .setDescription(product.getDescription() == null ? "" : product.getDescription())
                // The same price, machine-readable. DeviceLK-Commerce reads these two
                // rather than parsing the display string, so the integer it stores in
                // a cart line is byte-for-byte the one held here.
                .setPriceCents(product.getPriceCents())
                .setCurrency(product.getCurrency())
                .build();
    }
}
