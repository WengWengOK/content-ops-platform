package com.contentops.common.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for file I/O tools.
 *
 * <p>Bindable via application.yml under {@code contentops.file-tools}:
 * <pre>
 * contentops:
 *   file-tools:
 *     base-dir: ./content-outputs
 *     allowed-extensions: md,json,txt,csv
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.file-tools")
public class FileToolsProperties {

    /** Base directory for file operations (relative paths are resolved against this) */
    private String baseDir = "./content-outputs";

    /** Comma-separated list of allowed file extensions (security: prevents writing arbitrary files) */
    private String allowedExtensions = "md,json,txt,csv";
}
