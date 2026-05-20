package com.devicelk.productservice.repository;

import com.devicelk.productservice.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * <b>Repository</b> layer for {@link Product}.
 * <p>
 * Extending {@link JpaRepository} provides ready-made CRUD operations
 * ({@code save}, {@code findById}, {@code findAll}, {@code deleteById}, ...)
 * without any boilerplate implementation. Spring Data generates the proxy
 * at runtime.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
}
