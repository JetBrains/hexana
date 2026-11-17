package org.jetbrains.hexana.providers.sbom.vuln

/** OSV API and data-source URL constants shared by JVM (idea-plugin) and browser (webapp). */
object OsvConstants {
    /** POST endpoint for batch purl queries (up to [MAX_BATCH_SIZE] purls per request). */
    const val QUERY_BATCH_URL = "https://api.osv.dev/v1/querybatch"

    /** GET endpoint prefix for individual advisory records: append the advisory id. */
    const val VULN_BY_ID_URL = "https://api.osv.dev/v1/vulns/"

    /** GCS export zip for the Maven ecosystem (OSV offline database). */
    const val MAVEN_ALL_ZIP_URL = "https://osv-vulnerabilities.storage.googleapis.com/Maven/all.zip"

    /** Maximum number of purls per querybatch request (OSV API limit). */
    const val MAX_BATCH_SIZE = 1000
}