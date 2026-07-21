package com.devicelk.inventory.service;

import com.devicelk.inventory.api.ProductResponseDTO;
import com.devicelk.inventory.api.ProductWriteRequest;
import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Money;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.domain.Stock;
import com.devicelk.inventory.exception.ProductNotFoundException;
import com.devicelk.inventory.exception.StockNotFoundException;
import com.devicelk.inventory.repository.ProductRepository;
import com.devicelk.inventory.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link ProductService}.
 * <p>
 * Holds the CRUD business logic and is responsible for mapping the
 * {@link Product} JPA entity to the {@link ProductResponseDTO} exposed by
 * the API. Constructor injection is used so the dependency is final and the
 * class stays easy to test.
 * <p>
 * Package-private on purpose: only the {@link ProductService} interface is
 * visible outside this package, keeping the implementation an internal detail
 * of the inventory module.
 */
@Service
class ProductServiceImpl implements ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    private final ProductRepository productRepository;
    private final StockRepository stockRepository;

    public ProductServiceImpl(ProductRepository productRepository,
                              StockRepository stockRepository) {
        this.productRepository = productRepository;
        this.stockRepository = stockRepository;
    }

    @Override
    @Transactional
    public ProductResponseDTO createProduct(ProductWriteRequest request) {
        // CRITICAL CHECK: Enforce uniqueness before saving to the database
        if (productRepository.existsByNameAndBrand(request.name(), request.brand())) {
            throw new IllegalArgumentException(
                    "A product with the name '" + request.name() + "' under brand '" + request.brand() + "' already exists in the inventory."
            );
        }

        Product savedProduct = productRepository.save(new Product(
                request.name(),
                request.brand(),
                request.category(),
                Money.toCents(request.price()),
                Product.DEFAULT_CURRENCY,
                request.description()));

        // Same transaction as the product insert: a product must never reach a
        // reader without the stock row that gives its quantities meaning.
        Stock savedStock = stockRepository.save(new Stock(
                savedProduct.getId(),
                request.stockQuantity(),
                request.minStockThreshold()));

        return mapToDTO(savedProduct, savedStock);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getAllProducts() {
        return mapToDTOs(productRepository.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return mapToDTO(product, stockRepository.findById(id).orElse(null));
    }

    @Override
    @Transactional
    public ProductResponseDTO updateProduct(Long id, ProductWriteRequest request) {
        Product existing = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        Stock stock = stockRepository.findById(id)
                .orElseThrow(() -> new StockNotFoundException(id));

        // Only run the uniqueness check when the name/brand pair actually
        // changes — otherwise existsByNameAndBrand would match this very
        // product and reject a legitimate update.
        boolean identityChanged =
                !existing.getName().equals(request.name())
                        || !existing.getBrand().equals(request.brand());
        if (identityChanged
                && productRepository.existsByNameAndBrand(
                        request.name(), request.brand())) {
            throw new IllegalArgumentException(
                    "A product with the name '" + request.name()
                            + "' under brand '" + request.brand()
                            + "' already exists in the inventory."
            );
        }

        existing.setName(request.name());
        existing.setBrand(request.brand());
        existing.setCategory(request.category());
        existing.setPriceCents(Money.toCents(request.price()));
        existing.setDescription(request.description());

        // A PUT sets the quantity outright rather than applying a delta — the
        // same semantics this endpoint had when stock lived on the product.
        // Relative movements go through adjustStock, which enforces the floor.
        stock.setAvailableQty(request.stockQuantity());
        stock.setMinStockThreshold(request.minStockThreshold());

        Product updated = productRepository.save(existing);
        Stock updatedStock = stockRepository.save(stock);
        return mapToDTO(updated, updatedStock);
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        productRepository.deleteById(id);
    }

    @Override
    @Transactional
    public ProductResponseDTO adjustStock(Long id, Integer quantityChange) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        Stock stock = stockRepository.findById(id)
                .orElseThrow(() -> new StockNotFoundException(id));

        // The invariant lives on the entity; a movement that would go negative
        // throws IllegalArgumentException, which maps to HTTP 409 as before.
        stock.adjustAvailableQty(quantityChange);
        Stock updated = stockRepository.save(stock);

        // Low-stock alert: non-blocking warning when stock hits or falls below
        // the configured re-order threshold. Placeholder for a future Kafka
        // notification topic — the transaction is intentionally NOT aborted.
        if (updated.isLowStock()) {
            log.warn("[LOW STOCK ALERT] Product ID: {}, Name: {} is running low! "
                            + "Current Stock: {}, Threshold: {}",
                    product.getId(),
                    product.getName(),
                    updated.getAvailableQty(),
                    updated.getMinStockThreshold());
        }

        return mapToDTO(product, updated);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getProductsByIds(List<Long> ids) {
        return mapToDTOs(productRepository.findByIdIn(ids));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> searchProducts(String name,
                                                   String brand,
                                                   String category,
                                                   BigDecimal minPrice,
                                                   BigDecimal maxPrice,
                                                   Pageable pageable) {
        // Build the query dynamically: start from an always-true predicate and
        // AND in a restriction only when its parameter was actually supplied.
        Specification<Product> spec = (root, query, cb) -> cb.conjunction();

        if (name != null && !name.isBlank()) {
            String pattern = "%" + name.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("name")), pattern));
        }

        if (brand != null && !brand.isBlank()) {
            String brandValue = brand.trim().toLowerCase();
            spec = spec.and((root, query, cb) ->
                    cb.equal(cb.lower(root.get("brand")), brandValue));
        }

        if (category != null && !category.isBlank()) {
            // Resolve the enum up front; an invalid value surfaces as an
            // IllegalArgumentException, which the GlobalExceptionHandler maps.
            Category categoryEnum = Category.valueOf(category.trim().toUpperCase());
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("category"), categoryEnum));
        }

        if (minPrice != null) {
            long minPriceCents = toCentsOrReject(minPrice, "minPrice");
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("priceCents"), minPriceCents));
        }

        if (maxPrice != null) {
            long maxPriceCents = toCentsOrReject(maxPrice, "maxPrice");
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("priceCents"), maxPriceCents));
        }

        Page<Product> page = productRepository.findAll(spec, remapPriceSort(pageable));

        // One stock query for the whole page, not one per product.
        Map<Long, Stock> stockByProductId = stockByProductId(page.getContent());
        return page.map(p -> mapToDTO(p, stockByProductId.get(p.getId())));
    }

    /**
     * Converts a caller-supplied price bound to cents, rejecting a fraction of
     * a cent as a bad request rather than letting {@link ArithmeticException}
     * escape as a 500.
     */
    private static long toCentsOrReject(BigDecimal price, String fieldName) {
        try {
            return Money.toCents(price);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Invalid " + fieldName + " '" + price.toPlainString()
                            + "': more than 2 decimal places.");
        }
    }

    /**
     * Rewrites a {@code sort=price} directive onto the {@code priceCents}
     * property.
     * <p>
     * {@code price} is no longer a field of {@link Product}, so Spring Data
     * would reject the sort outright — and clients (the storefront's
     * "Price: Low to High") have been sending it all along. Cents are a
     * strictly increasing function of price, so the resulting order is
     * identical to the one those clients already receive.
     */
    private static Pageable remapPriceSort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        Sort remapped = Sort.by(pageable.getSort().stream()
                .map(order -> "price".equals(order.getProperty())
                        ? new Sort.Order(order.getDirection(), "priceCents")
                        : order)
                .toList());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), remapped);
    }

    /**
     * Maps a batch of products, resolving every stock row in a single query so
     * the cost stays flat as the batch grows.
     */
    private List<ProductResponseDTO> mapToDTOs(List<Product> products) {
        Map<Long, Stock> stockByProductId = stockByProductId(products);
        return products.stream()
                .map(p -> mapToDTO(p, stockByProductId.get(p.getId())))
                .toList();
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
     * Maps a persisted entity and its stock row to the immutable response DTO.
     * <p>
     * {@code price} is rebuilt from cents through {@link Money}, then wrapped in
     * a {@code BigDecimal} so Jackson still writes it as an unquoted JSON number
     * with two decimals — the exact bytes clients received when the column was
     * {@code numeric(12,2)}.
     *
     * @param stock the product's stock row, or {@code null} if it has none — a
     *              read degrades to zeroes and a warning rather than failing the
     *              whole response over one inconsistent row
     */
    private ProductResponseDTO mapToDTO(Product p, Stock stock) {
        if (stock == null) {
            log.warn("Stock record missing for product ID: {} ({}); reporting zero quantities",
                    p.getId(), p.getName());
        }
        return new ProductResponseDTO(
                p.getId(),
                p.getName(),
                p.getBrand(),
                p.getCategory(),
                new BigDecimal(Money.toDisplayString(p.getPriceCents())),
                stock == null ? 0 : stock.getAvailableQty(),
                stock == null ? 0 : stock.getMinStockThreshold(),
                p.getDescription()
        );
    }
}
