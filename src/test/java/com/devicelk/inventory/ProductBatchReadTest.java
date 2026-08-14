package com.devicelk.inventory;

import com.devicelk.AbstractPostgresTest;
import com.devicelk.inventory.api.ProductWriteRequest;
import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.service.ProductService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ProductService#getProductSnapshots(List)} does not degrade into
 * an N+1, by counting the statements Hibernate actually prepares.
 * <p>
 * This assertion used to be made against {@code InventoryFacade}, the published
 * API the cart and order modules read the catalogue through. That facade was
 * deleted when those modules became DeviceLK-Commerce — it existed to serve other
 * modules in this process, and there are none. The batched read itself did not
 * move: it is still here, still the thing every catalogue lookup goes through, and
 * now reached over gRPC instead of by method call. A remote caller is the one that
 * can least afford an N+1, so this test matters more than it did, not less.
 * <p>
 * Asserting on a query count rather than on the returned data because the
 * returned data looks identical either way — a per-row lookup and a batched one
 * produce the same list, and only the statement count tells them apart. This is
 * the assertion that would fail if someone later reimplemented the batch method
 * as a loop over {@code getProduct}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "grpc.server.port=-1",
                // Off by default; without it Statistics reports nothing.
                "spring.jpa.properties.hibernate.generate_statistics=true"
        }
)
class ProductBatchReadTest extends AbstractPostgresTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void reset() {
        // Stock before products: the FK is ON DELETE CASCADE, but deleting the
        // child first keeps the intent explicit rather than relying on it.
        // No cart tables to clear — this database holds the inventory schema alone.
        jdbcTemplate.execute("DELETE FROM inventory.stock");
        jdbcTemplate.execute("DELETE FROM inventory.products");

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    /** Creates {@code count} products and returns their ids. */
    private List<Long> seedProducts(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(productService.createProduct(new ProductWriteRequest(
                    "Batch Product " + i + "-" + System.nanoTime(),
                    "BatchBrand",
                    Category.ACCESSORIES,
                    new BigDecimal("1200.50"),
                    10 + i,
                    2,
                    "Seeded by ProductBatchReadTest")).id());
        }
        return ids;
    }

    @Test
    @DisplayName("reads any number of products in a fixed two queries")
    void batchIsFlatInBatchSize() {
        List<Long> fewIds = seedProducts(2);

        statistics.clear();
        List<ProductSnapshot> few = productService.getProductSnapshots(fewIds);
        long queriesForTwo = statistics.getPrepareStatementCount();

        assertThat(few).hasSize(2);
        assertThat(queriesForTwo)
                .as("one query for products, one for their stock rows")
                .isEqualTo(2);

        List<Long> manyIds = new ArrayList<>(fewIds);
        manyIds.addAll(seedProducts(8));

        statistics.clear();
        List<ProductSnapshot> many = productService.getProductSnapshots(manyIds);
        long queriesForTen = statistics.getPrepareStatementCount();

        assertThat(many).hasSize(10);
        assertThat(queriesForTen)
                .as("five times the products must not mean five times the queries")
                .isEqualTo(queriesForTwo);
    }

    @Test
    @DisplayName("carries price in cents, currency and availability")
    void snapshotCarriesMoneyAndStock() {
        Long id = seedProducts(1).get(0);

        ProductSnapshot snapshot = productService.getProductSnapshots(List.of(id)).get(0);

        assertThat(snapshot.productId()).isEqualTo(id);
        assertThat(snapshot.priceCents())
                .as("1200.50 in minor units, never a floating-point round trip")
                .isEqualTo(120050L);
        assertThat(snapshot.currency()).isEqualTo("LKR");
        assertThat(snapshot.availableQty()).isEqualTo(10);
    }

    @Test
    @DisplayName("omits ids that do not exist rather than failing the batch")
    void missingIdsAreOmitted() {
        List<Long> ids = seedProducts(2);
        List<Long> withGhost = new ArrayList<>(ids);
        withGhost.add(987654321L);

        List<ProductSnapshot> found = productService.getProductSnapshots(withGhost);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(ProductSnapshot::productId)
                .containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    @DisplayName("an empty request issues no query at all")
    void emptyRequestShortCircuits() {
        statistics.clear();

        assertThat(productService.getProductSnapshots(List.of())).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }
}
