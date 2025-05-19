CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE embeddings (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGSERIAL,
    user_id BIGSERIAL,
    text TEXT,
    meta_data JSONB,
    embedding vector(1536)
);