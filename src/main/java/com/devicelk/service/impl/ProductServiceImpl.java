package com.devicelk.service.impl;

import com.devicelk.domain.Product;
import com.devicelk.dto.ProductResponseDTO;
import com.devicelk.exception.ProductNotFoundException;
import com.devicelk.repository.ProductRepository;
import com.devicelk.service.ProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Default implementation of {@link ProductService}.
 * <p>
 * Holds the CRUD business logic and is responsible for mapping the
 * {@link Product} JPA entity to the {@link ProductResponseDTO} exposed by
 * the API. Constructor injection is used so the dependency is final and the
 * class stays easy to test.
 */
@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    public ProductServiceImpl(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public ProductResponseDTO createProduct(Product product) {
        // CRITICAL CHECK: Enforce uniqueness before saving to the database
        if (productRepository.existsByNameAndBrand(product.getName(), product.getBrand())) {
            throw new IllegalArgumentException(
                    "A product with the name '" + product.getName() + "' under brand '" + product.getBrand() + "' already exists in the inventory."
            );
        }

        Product savedProduct = productRepository.save(product);
        return mapToDTO(savedProduct);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getAllProducts() {
        return productRepository.findAll().stream()
                .map(this::mapToDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return mapToDTO(product);
    }

    @Override
    @Transactional
    public ProductResponseDTO updateProduct(Long id, Product productDetails) {
        Product existing = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        // Only run the uniqueness check when the name/brand pair actually
        // changes — otherwise existsByNameAndBrand would match this very
        // product and reject a legitimate update.
        boolean identityChanged =
                !existing.getName().equals(productDetails.getName())
                        || !existing.getBrand().equals(productDetails.getBrand());
        if (identityChanged
                && productRepository.existsByNameAndBrand(
                        productDetails.getName(), productDetails.getBrand())) {
            throw new IllegalArgumentException(
                    "A product with the name '" + productDetails.getName()
                            + "' under brand '" + productDetails.getBrand()
                            + "' already exists in the inventory."
            );
        }

        existing.setName(productDetails.getName());
        existing.setBrand(productDetails.getBrand());
        existing.setCategory(productDetails.getCategory());
        existing.setPrice(productDetails.getPrice());
        existing.setStockQuantity(productDetails.getStockQuantity());
        existing.setDescription(productDetails.getDescription());

        Product updated = productRepository.save(existing);
        return mapToDTO(updated);
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

        int newStock = product.getStockQuantity() + quantityChange;
        if (newStock < 0) {
            throw new IllegalArgumentException("Insufficient stock!");
        }

        product.setStockQuantity(newStock);
        Product updated = productRepository.save(product);
        return mapToDTO(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getProductsByIds(List<Long> ids) {
        return productRepository.findByIdIn(ids).stream()
                .map(this::mapToDTO)
                .toList();
    }

    /** Maps a persisted entity to the immutable response DTO. */
    private ProductResponseDTO mapToDTO(Product p) {
        return new ProductResponseDTO(
                p.getId(),
                p.getName(),
                p.getBrand(),
                p.getCategory(),
                p.getPrice(),
                p.getStockQuantity(),
                p.getDescription()
        );
    }
}
