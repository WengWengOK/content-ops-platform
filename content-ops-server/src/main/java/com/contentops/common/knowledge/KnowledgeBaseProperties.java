package com.contentops.common.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the RAG knowledge base backed by PGVector.
 *
 * <p>Bindable via application.yml under {@code contentops.knowledge-base}:
 * <pre>
 * contentops:
 *   knowledge-base:
 *     pg-host: localhost
 *     pg-port: 5432
 *     pg-database: contentops
 *     pg-user: contentops
 *     pg-password: ${DATABASE_PASSWORD}  # 必须通过环境变量注入，禁止硬编码
 *     table-name: content_embeddings
 *     max-results: 5
 *     min-score: 0.7
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.knowledge-base")
public class KnowledgeBaseProperties {

    /** PostgreSQL host for the vector store */
    private String pgHost = "localhost";

    /** PostgreSQL port */
    private int pgPort = 5432;

    /** PostgreSQL database name */
    private String pgDatabase = "contentops";

    /** PostgreSQL username */
    private String pgUser = "contentops";

    /** PostgreSQL password — must be injected via environment variable, no hardcoded default */
    private String pgPassword = "";

    /** Table name for storing embeddings in PGVector */
    private String tableName = "content_embeddings";

    /** Maximum number of results to return from similarity search */
    private int maxResults = 5;

    /** Minimum similarity score (0.0 - 1.0); results below this threshold are filtered out */
    private double minScore = 0.6;

    /** Whether to drop and recreate the table on startup (useful for development) */
    private boolean dropTableFirst = false;

    /** Whether to create the table if it doesn't exist */
    private boolean createTable = true;

    /** Whether to use HNSW index for faster similarity search */
    private boolean useIndex = true;
}
