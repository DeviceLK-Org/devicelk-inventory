-- Links a product to its spec document (.md) in the S3 bucket that backs the
-- Bedrock knowledge base, e.g. 'product-7/ASUS_ROG_Strix_Specs.md'.
--
-- Only the key is stored, never the bucket: which bucket is configuration
-- (app.documents.bucket), not data, and baking it into every row would make
-- moving buckets a data migration instead of a config change.
--
-- Nullable because every product that exists today predates the feature and has
-- no document, and because deleting a document clears the reference back to null.
ALTER TABLE inventory.products ADD COLUMN document_key VARCHAR(512);
