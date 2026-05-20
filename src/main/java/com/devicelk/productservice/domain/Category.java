package com.devicelk.productservice.domain;

/**
 * Enumerates the product categories supported by the DeviceLK inventory.
 * <p>
 * Using an enum (instead of a free-text {@code String}) guarantees type-safety:
 * only these five values can ever be persisted, eliminating typos and invalid
 * categories at compile time.
 */
public enum Category {
    LAPTOP,
    SMARTPHONE,
    TABLET,
    ACCESSORIES,
    AUDIO_DEVICE
}
