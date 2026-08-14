package com.devicelk.inventory.service;

import com.devicelk.inventory.api.ProductResponseDTO;

/**
 * Storage lifecycle for product spec documents — the {@code .md} sheets that
 * feed the Bedrock knowledge base behind the AI assistant.
 * <p>
 * Takes a filename and raw bytes rather than a {@code MultipartFile} so the
 * servlet API stays in the controller and this contract can be exercised
 * without any web scaffolding.
 * <p>
 * <b>Storing a document does not make it answerable.</b> The knowledge base only
 * picks up changes when an ingestion job runs, so an upload leaves the file
 * visible to this service and invisible to the AI until a sync happens. That gap
 * is deliberate and surfaced to admins rather than hidden.
 */
public interface ProductDocumentService {

    /**
     * Stores {@code content} as the product's spec document, replacing any
     * document it already had, and records the resulting key on the product.
     *
     * @param productId        the product the document describes
     * @param originalFilename client-supplied filename; reduced to a safe leaf name
     * @param content          the raw {@code .md} bytes
     * @return the product as it now stands, including its {@code documentKey}
     * @throws com.devicelk.inventory.exception.ProductNotFoundException  no such product
     * @throws com.devicelk.inventory.exception.InvalidDocumentException  not a usable .md file
     * @throws com.devicelk.inventory.exception.DocumentStorageException  S3 refused or was unreachable
     */
    ProductResponseDTO uploadDocument(Long productId, String originalFilename, byte[] content);
}
