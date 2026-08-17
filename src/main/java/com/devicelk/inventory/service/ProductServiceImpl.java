package com.devicelk.inventory.service;

import com.devicelk.inventory.ProductSnapshot;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default {@link ProductService}. Package-private; callers bind to the interface.
 * <p>
 * Holds the CRUD logic and maps {@link Product} entities onto the
 * {@link ProductResponseDTO} the API exposes.
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
        // Enforce name/brand uniqueness before saving.
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

        // Only check uniqueness when the name/brand pair changes, or
        // existsByNameAndBrand would match this very product and reject the update.
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

        // A PUT sets the quantity outright; relative movements go through
        // adjustStock, which enforces the never-negative floor.
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

        // The invariant lives on the entity; going negative throws
        // IllegalArgumentException, which maps to HTTP 409.
        stock.adjustAvailableQty(quantityChange);
        Stock updated = stockRepository.save(stock);

        // Warning only — the transaction is deliberately not aborted. Placeholder
        // for a future notification topic.
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
    public Optional<ProductSnapshot> getProductSnapshot(Long id) {
        return productRepository.findById(id).map(product -> {
            Stock stock = stockRepository.findById(id).orElse(null);
            if (stock == null) {
                log.warn("Stock record missing for product ID: {} ({}); reporting zero availability",
                        product.getId(), product.getName());
            }
            return new ProductSnapshot(
                    product.getId(),
                    product.getName(),
                    product.getPriceCents(),
                    product.getCurrency(),
                    // No stock row means nothing is known to be sellable; zero
                    // blocks a sale rather than waving one through.
                    stock == null ? 0 : stock.getAvailableQty());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> searchProducts(String name,
                                                   String brand,
                                                   String category,
                                                   BigDecimal minPrice,
                                                   BigDecimal maxPrice,
                                                   Pageable pageable) {
        // Start from an always-true predicate and AND in each restriction only
        // when its parameter was supplied.
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
            // Resolve up front; an invalid value throws IllegalArgumentException,
            // which GlobalExceptionHandler maps.
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

    @Override
    @Transactional(readOnly = true)
    public List<ProductSnapshot> getProductSnapshots(List<Long> ids) {
        if (ids.isEmpty()) {
            // An empty IN () is pointless and, on some dialects, invalid SQL.
            return List.of();
        }
        List<Product> products = productRepository.findByIdIn(ids);
        // One stock query for the whole batch, so this stays two queries
        // regardless of batch size.
        Map<Long, Stock> stockByProductId = stockByProductId(products);
        return products.stream()
                .map(product -> {
                    Stock stock = stockByProductId.get(product.getId());
                    return new ProductSnapshot(
                            product.getId(),
                            product.getName(),
                            product.getPriceCents(),
                            product.getCurrency(),
                            stock == null ? 0 : stock.getAvailableQty());
                })
                .toList();
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
     * Rewrites a {@code sort=price} directive onto {@code priceCents}.
     * <p>
     * {@code price} is not a field of {@link Product}, so Spring Data would
     * reject the sort — but clients still send it. Cents increase strictly with
     * price, so the resulting order is identical.
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
     * Maps an entity and its stock row to the response DTO.
     * <p>
     * {@code price} is rebuilt from cents through {@link Money} and wrapped in a
     * {@code BigDecimal} so Jackson writes an unquoted JSON number with two
     * decimals, matching what clients received when the column was
     * {@code numeric(12,2)}.
     *
     * @param stock the product's stock row, or {@code null} if it has none — the
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
                p.getDescription(),
                p.getDocumentKey()
        );
    }
}
