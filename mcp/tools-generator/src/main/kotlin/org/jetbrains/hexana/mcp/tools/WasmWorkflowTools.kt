package org.jetbrains.hexana.mcp.tools

val ValidateAndExplainToolId = ToolId("validate_and_explain")
val MetadataAuditToolId = ToolId("metadata_audit")
val ComponentWitReportToolId = ToolId("component_wit_report")

private val workflowSource = class_("WasmWorkflowInput") {
    inputCapabilities(
        InputConversionCapability.JsonObject,
        InputConversionCapability.WasmPath,
        InputConversionCapability.WasmFromString,
        InputConversionCapability.Stdin,
    )
    nullable("path", string()).describe("Readable local `.wasm` path, or `-` for stdin.")
    nullable("bytesBase64", string()).describe("Internal Base64 Wasm source.")
        .maxLength(SchemaIntValue.MaxInlineWasmBase64Length)
    nullable("bytes", list(of = int())).describe("Internal raw Wasm source.")
        .itemsMinimum(0).itemsMaximum(255).maxItems(SchemaIntValue.MaxInlineWasmBytes)
}
private val workflowInput = workflowSource.withExactOneSourceGroup(
    "wasm input",
    pathSource(workflowSource.fieldRef("path")),
    base64Source(workflowSource.fieldRef("bytesBase64")),
    bytesSource(workflowSource.fieldRef("bytes")),
)
val ValidateAndExplainInputClass = workflowInput.with("ValidateAndExplainInput") {
    nullable("runtimes", list(of = string())).describe("Explicit runtime targets in supplied order.").maxItems(500)
}
val ComponentWitReportInputClass = workflowInput.with("ComponentWitReportInput") {
    nullable("dependencyPaths", list(of = string())).describe("Explicit component dependency paths in supplied order.").maxItems(500)
}

private val product = class_("WasmToolsProduct") {
    string("id").describe("The `hexana-wasm-tools` product identity.")
    string("version").describe("Shared Hexana tools release version.")
}
private val report = class_("WasmToolsReportIdentity") {
    string("workflow").describe("One of the three focused Wasm workflows.")
    int("schemaVersion").serialName("schema_version").describe("Workflow report schema version, initially 1.")
}
private val input = class_("WasmToolsReportInput") {
    string("kind").describe("`path` or `stdin`.")
    requiredNullable("path", string()).describe("Path label, or null for stdin.")
    long("byteCount").serialName("byte_count").describe("Selected input size in bytes.")
}
private val completeness = class_("WasmToolsCompleteness") {
    string("status").describe("`complete` or `partial`.")
    list("limitations", of = string()).describe("Factual report limitations.")
}
private val diagnostic = class_("WasmToolsDiagnostic") {
    string("severity").describe("`warning` or `error`.")
    string("category").describe("Diagnostic category.")
    string("code").describe("Stable diagnostic code.")
    string("message").describe("Diagnostic explanation.")
    requiredNullable("byteOffset", long()).serialName("byte_offset").describe("Input byte offset, when known.")
    requiredNullable("sectionId", int()).serialName("section_id").describe("Section ID, when known.")
}
private val finding = class_("WasmToolsFinding") {
    string("severity").describe("`info`, `warning`, or `error`.")
    string("category").describe("Finding category.")
    string("code").describe("Stable finding code.")
    string("message").describe("Finding explanation.")
    requiredNullable("byteOffset", long()).serialName("byte_offset").describe("Input byte offset, when known.")
}
private val omitted = class_("WasmToolsOmittedCounts") {
    int("diagnostics").describe("Omitted diagnostic count.")
    int("findings").describe("Omitted finding count.")
    int("workflowItems").serialName("workflow_items").describe("Omitted workflow item count.")
}
private val truncation = class_("WasmToolsTruncation") {
    boolean("truncated").describe("Whether report items or bytes were omitted.")
    list("causes", of = string()).describe("`max_items` and/or `max_output_bytes`.")
    embed("omitted", of = omitted).describe("Counts of omitted records.")
}
private fun ClassBuilder.envelope() {
    embed("product", of = product).describe("Release product identity.")
    embed("report", of = report).describe("Workflow and schema identity.")
    embed("input", of = input).describe("Selected source and byte count.")
    embed("completeness", of = completeness).describe("Completeness status and limitations.")
    list("diagnostics", of = diagnostic).describe("Ordered parser and workflow diagnostics.")
    list("findings", of = finding).describe("Ordered user-level conclusions.")
    embed("truncation", of = truncation).describe("Output omission state and counts.")
}

private val coreSummary = class_("WasmToolsCoreSummary") {
    string("stage").describe("`completed` or `not_applicable`.")
    requiredNullable("functions", int()).describe("Recovered function count.")
    requiredNullable("exports", int()).describe("Recovered export count.")
    requiredNullable("memories", int()).describe("Recovered memory count.")
    requiredNullable("globals", int()).describe("Recovered global count.")
    requiredNullable("tables", int()).describe("Recovered table count.")
    requiredNullable("types", int()).describe("Recovered type count.")
}
private val componentSummary = class_("WasmToolsComponentSummary") {
    string("stage").describe("`completed` or `not_applicable`.")
    requiredNullable("imports", int()).describe("Recovered import count.")
    requiredNullable("exports", int()).describe("Recovered export count.")
    requiredNullable("types", int()).describe("Recovered type count.")
    requiredNullable("nestedComponents", int()).serialName("nested_components").describe("Recovered nested component count.")
}
private val summary = class_("WasmToolsStructuralSummary") {
    string("kind").describe("`core_module` or `component`.")
    int("version").describe("Wasm binary version.")
    int("layer").describe("Component layer or core layer.")
    int("sectionCount").serialName("section_count").describe("Recovered section count.")
    int("customSectionCount").serialName("custom_section_count").describe("Recovered custom section count.")
    int("unresolvedSectionCount").serialName("unresolved_section_count").describe("Unresolved section count.")
    embed("coreModule", of = coreSummary).serialName("core_module").describe("Core-only counts.")
    embed("component", of = componentSummary).describe("Component-only counts.")
}
private val scanFailure = class_("WasmToolsSectionScanFailure") {
    int("byteOffset").serialName("byte_offset").describe("Failure byte offset.")
    string("message").describe("Section scan failure.")
}
private val parseProblem = class_("WasmToolsParseProblem") {
    string("kind").describe("Parse problem kind.")
    int("sectionId").serialName("section_id").describe("Section ID.")
    string("sectionName").serialName("section_name").describe("Section name.")
    requiredNullable("functionIndex", int()).serialName("function_index").describe("Function index, when known.")
    requiredNullable("typeIndex", int()).serialName("type_index").describe("Type index, when known.")
    int("byteOffset").serialName("byte_offset").describe("Problem byte offset.")
    int("byteSize").serialName("byte_size").describe("Affected byte length.")
    string("message").describe("Parse problem.")
}
private val structuralParse = class_("WasmToolsStructuralParse") {
    string("status").describe("`clean` or `partial`.")
    list("sectionScanFailures", of = scanFailure).serialName("section_scan_failures").describe("Section scan failures.")
    list("parseProblems", of = parseProblem).serialName("parse_problems").describe("Provider parse problems.")
}
private val enabledFeatures = class_("WasmToolsEnabledFeatures") {
    boolean("referenceTypes").serialName("reference_types").describe("Reference types observed.")
    boolean("bulkMemory").serialName("bulk_memory").describe("Bulk memory observed.")
    boolean("multiValue").serialName("multi_value").describe("Multi-value observed.")
    boolean("signExtension").serialName("sign_extension").describe("Sign extension observed.")
    boolean("simd").describe("SIMD observed.")
    boolean("threads").describe("Thread features observed.")
    boolean("memory64").describe("Memory64 observed.")
    boolean("gcTypes").serialName("gc_types").describe("GC types observed.")
    boolean("subtyping").describe("Subtyping observed.")
    boolean("functionReferences").serialName("function_references").describe("Function references observed.")
    boolean("extendedConst").serialName("extended_const").describe("Extended constant expressions observed.")
}
private val featureSources = class_("WasmToolsFeatureSources") {
    list("sections", of = string()).describe("Sections contributing feature evidence.")
    list("instructions", of = string()).describe("Instructions contributing feature evidence.")
    list("customSections", of = string()).serialName("custom_sections").describe("Custom sections contributing feature evidence.")
}
private val proposalEvidence = class_("WasmToolsProposalEvidence") {
    string("featureFlag").serialName("feature_flag").describe("Feature flag associated with the proposal.")
    list("sections", of = string()).describe("Section evidence.")
    list("instructions", of = string()).describe("Instruction evidence.")
    list("customSections", of = string()).serialName("custom_sections").describe("Custom-section evidence.")
    list("notes", of = string()).describe("Additional evidence notes.")
}
private val requiredProposal = class_("WasmToolsRequiredProposal") {
    string("proposal").describe("Proposal name.")
    boolean("required").describe("Always true for a reported required proposal.")
    embed("evidence", of = proposalEvidence).describe("Evidence for the requirement.")
}
private val featuresAndProposals = class_("WasmToolsFeaturesAndProposals") {
    string("stage").describe("`completed` or `not_applicable`.")
    requiredNullable("enabledFeatures", enabledFeatures).serialName("enabled_features").describe("Core feature flags, when applicable.")
    requiredNullable("enabledFeatureSources", featureSources).serialName("enabled_feature_sources").describe("Core feature evidence, when applicable.")
    list("requiredProposals", of = requiredProposal).serialName("required_proposals").describe("Required proposals.")
}
private val runtimeTarget = class_("WasmToolsRuntimeTarget") {
    string("runtime").describe("Explicit runtime name.")
}
private val proposalStatus = class_("WasmToolsRuntimeProposalStatus") {
    string("proposal").describe("Proposal name.")
    string("status").describe("`supported`, `unsupported`, or `unknown`.")
    string("note").describe("Runtime support note.")
    embed("evidence", of = proposalEvidence).describe("Proposal evidence.")
}
private val runtimeResult = class_("WasmToolsRuntimeResult") {
    string("runtime").describe("Runtime name.")
    boolean("compatible").describe("Whether all required proposals are supported.")
    list("requiredProposals", of = string()).serialName("required_proposals").describe("Required proposals.")
    list("unsupportedProposals", of = string()).serialName("unsupported_proposals").describe("Unsupported proposals.")
    list("unknownProposals", of = string()).serialName("unknown_proposals").describe("Proposals with unknown support.")
    list("proposalStatuses", of = proposalStatus).serialName("proposal_statuses").describe("Per-proposal compatibility evidence.")
}
private val runtimeCompatibility = class_("WasmToolsRuntimeCompatibility") {
    string("stage").describe("`not_requested`, `completed`, or `not_applicable`.")
    list("targets", of = runtimeTarget).describe("Requested targets.")
    list("results", of = runtimeResult).describe("Compatibility results.")
}
val ValidateAndExplainResultClass = class_("ValidateAndExplainReport") {
    envelope()
    embed("summary", of = summary).describe("Parser-backed structural counts.")
    embed("structuralParse", of = structuralParse).serialName("structural_parse").describe("Structural parse evidence.")
    string("semanticValidation").serialName("semantic_validation").describe("Always `not_performed`.")
    embed("featuresAndProposals", of = featuresAndProposals).serialName("features_and_proposals").describe("Core feature evidence.")
    embed("runtimeCompatibility", of = runtimeCompatibility).serialName("runtime_compatibility").describe("Explicit runtime checks.")
    outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.Pretty)
}

private val sectionPreview = class_("WasmToolsSectionPreview") {
    string("encoding").describe("`utf8` or `binary`.")
    string("value").describe("Bounded preview text.")
    int("capturedBytes").serialName("captured_bytes").describe("Payload bytes captured, at most 96.")
    boolean("truncated").describe("Whether payload bytes remain.")
}
private val customSection = class_("WasmToolsCustomSection") {
    string("name").describe("Encoded custom section name.")
    long("offsetBytes").serialName("offset_bytes").describe("Full section byte offset.")
    long("sizeBytes").serialName("size_bytes").describe("Full encoded section size.")
    embed("preview", of = sectionPreview).describe("Bounded payload preview.")
}
private val metadataDiagnostic = class_("WasmToolsMetadataDiagnostic") {
    string("message").describe("Metadata decode diagnostic.")
    requiredNullable("byteOffset", long()).serialName("byte_offset").describe("Byte offset, when known.")
}
private val producerValue = class_("WasmToolsProducerValue") {
    string("name").describe("Producer name.")
    string("version").describe("Producer version.")
}
private val producerField = class_("WasmToolsProducerField") {
    int("fieldIndex").serialName("field_index").describe("Encoded field index.")
    string("field").describe("Producer field name.")
    list("values", of = producerValue).describe("Encoded producer values.")
}
private val producerSection = class_("WasmToolsProducerSection") {
    int("occurrence").describe("Encoded section occurrence.")
    long("offsetBytes").serialName("offset_bytes").describe("Section byte offset.")
    string("status").describe("`decoded` or `malformed`.")
    requiredNullable("fields", list(of = producerField)).describe("Decoded fields, or null on failure.")
    list("diagnostics", of = metadataDiagnostic).describe("Decode diagnostics.")
}
private val typedProducers = class_("WasmToolsTypedProducers") {
    string("status").describe("`absent`, `decoded`, `partial`, or `malformed`.")
    list("sections", of = producerSection).describe("Encoded producer sections.")
}
private val targetFeature = class_("WasmToolsTargetFeature") {
    int("featureIndex").serialName("feature_index").describe("Encoded feature index.")
    string("name").describe("Feature name.")
    string("state").describe("`enabled` or `disabled`.")
}
private val targetFeatureSection = class_("WasmToolsTargetFeatureSection") {
    int("occurrence").describe("Encoded section occurrence.")
    long("offsetBytes").serialName("offset_bytes").describe("Section byte offset.")
    string("status").describe("`decoded` or `malformed`.")
    requiredNullable("features", list(of = targetFeature)).describe("Decoded features, or null on failure.")
    list("diagnostics", of = metadataDiagnostic).describe("Decode diagnostics.")
}
private val typedTargetFeatures = class_("WasmToolsTypedTargetFeatures") {
    string("status").describe("`absent`, `decoded`, `partial`, or `malformed`.")
    list("sections", of = targetFeatureSection).describe("Encoded target-features sections.")
}
private val typedMetadata = class_("WasmToolsTypedMetadata") {
    embed("producers", of = typedProducers).describe("Typed producers evidence.")
    embed("targetFeatures", of = typedTargetFeatures).serialName("target_features").describe("Typed target-features evidence.")
}
private val metadataEnabledFeatures = class_("WasmToolsMetadataEnabledFeatures") {
    string("stage").describe("`completed`, `partial`, or `not_applicable`.")
    requiredNullable("features", enabledFeatures).describe("Core feature flags, when available.")
    requiredNullable("sources", featureSources).describe("Core feature evidence, when available.")
}
val MetadataAuditResultClass = class_("MetadataAuditReport") {
    envelope()
    list("customSections", of = customSection).serialName("custom_sections").describe("Ordered custom section inventory.")
    embed("typedMetadata", of = typedMetadata).serialName("typed_metadata").describe("Typed metadata occurrences.")
    embed("enabledFeatures", of = metadataEnabledFeatures).serialName("enabled_features").describe("Core-only inferred features.")
    outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.Pretty)
}

private val surfaceEntry = class_("WasmToolsSurfaceEntry") {
    string("name").describe("Full component name.")
    string("baseName").serialName("base_name").describe("Name without version.")
    string("version").describe("Version suffix, or empty.")
    requiredNullable("sort", string()).describe("Component sort, when known.")
    requiredNullable("externDescription", string()).serialName("extern_description").describe("Extern description, when known.")
    boolean("runtimeProvided").serialName("runtime_provided").describe("Whether the runtime supplies this import.")
}
private val surfaceSummary = class_("WasmToolsSurfaceSummary") {
    int("importCount").serialName("import_count").describe("Total import count.")
    int("exportCount").serialName("export_count").describe("Total export count.")
    int("nonRuntimeImportCount").serialName("non_runtime_import_count").describe("Non-runtime import count.")
    int("runtimeImportCount").serialName("runtime_import_count").describe("Runtime import count.")
}
private val componentSurface = class_("WasmToolsComponentSurface") {
    boolean("isComponent").serialName("is_component").describe("Whether input is a component.")
    requiredNullable("layer", int()).describe("Component layer, when applicable.")
    list("imports", of = surfaceEntry).describe("Sorted component imports.")
    list("exports", of = surfaceEntry).describe("Sorted component exports.")
    embed("summary", of = surfaceSummary).describe("Pre-truncation surface counts.")
}
private val typeLimits = class_("WasmToolsTypeLimits") {
    long("maxDepth").serialName("max_depth").describe("Traversal depth budget.")
    long("maxNodes").serialName("max_nodes").describe("Decoded node budget.")
    long("visitedNodes").serialName("visited_nodes").describe("Visited decoded nodes.")
    long("omittedNodes").serialName("omitted_nodes").describe("Omitted decoded nodes.")
}
private val typeItem = class_("WasmToolsTypeItem") {
    long("index").describe("Original type index.")
    string("family").describe("Component type family.")
    string("rootNodeId").serialName("root_node_id").describe("Stable root node ID.")
}
private val nodeAttributes = class_("WasmToolsNodeAttributes") {
    listOf("prefix", "name", "version", "versionSuffix", "primitive", "sort", "coreSort", "targetKind",
        "boundKind", "externKind", "externalKind", "limitsKind", "valueKind", "referenceForm", "heapKind",
        "tag", "reason").forEach { name -> nullable(name, string()).serialName(name.camelToSnake()).describe("Optional $name attribute.") }
    listOf("offsetBytes", "sizeBytes", "typeIndex", "index", "outerIndex", "length", "functionIndex",
        "callbackIndex", "min", "max", "pageSizeLog2", "heapType", "omittedNodeCount").forEach { name ->
        nullable(name, long()).serialName(name.camelToSnake()).describe("Optional $name attribute.")
    }
    nullable("externType", int()).serialName("extern_type").describe("Optional external type byte.")
    listOf("mutable", "shared", "isFinal", "hasDescriptorClause").forEach { name ->
        nullable(name, boolean()).serialName(name.camelToSnake()).describe("Optional $name attribute.")
    }
}
private fun String.camelToSnake(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
private val typeNode = class_("WasmToolsTypeNode") {
    string("nodeId").serialName("node_id").describe("Stable node ID.")
    requiredNullable("parentNodeId", string()).serialName("parent_node_id").describe("Parent node ID, or null for a root.")
    requiredNullable("childRole", string()).serialName("child_role").describe("Role within the parent, when present.")
    requiredNullable("childIndex", long()).serialName("child_index").describe("Index within the role, when present.")
    string("kind").describe("Decoded node kind or `omitted`.")
    embed("attributes", of = nodeAttributes).describe("Known scalar node attributes.")
}
private val componentTypes = class_("WasmToolsComponentTypes") {
    string("stage").describe("`completed` or `not_applicable`.")
    requiredNullable("limits", typeLimits).describe("Traversal limits, when applicable.")
    list("items", of = typeItem).describe("Original type-index order.")
    list("nodes", of = typeNode).describe("Flat component type graph.")
}
private val dependencyMatch = class_("WasmToolsDependencyMatch") {
    string("importName").serialName("import_name").describe("Matched import.")
    string("dependencyPath").serialName("dependency_path").describe("Matched provider path.")
    string("exportName").serialName("export_name").describe("Matched export.")
    boolean("exactVersionMatch").serialName("exact_version_match").describe("Whether versions match exactly.")
}
private val missingImport = class_("WasmToolsMissingImport") {
    string("name").describe("Missing import name.")
    string("baseName").serialName("base_name").describe("Base import name.")
    string("version").describe("Required version.")
    boolean("runtimeProvided").serialName("runtime_provided").describe("Whether runtime supplies the import.")
}
private val dependencyIssue = class_("WasmToolsDependencyIssue") {
    string("importName").serialName("import_name").describe("Affected import.")
    requiredNullable("dependencyPath", string()).serialName("dependency_path").describe("Provider path, when available.")
    string("severity").describe("`warning` or `error`.")
    string("reason").describe("Stable compatibility reason.")
    requiredNullable("expected", string()).describe("Expected version or shape.")
    requiredNullable("actual", string()).describe("Actual version, provider, or shape.")
}
private val candidateProvider = class_("WasmToolsCandidateProvider") {
    string("dependencyPath").serialName("dependency_path").describe("Candidate component path.")
    string("exportName").serialName("export_name").describe("Candidate export name.")
    string("baseName").serialName("base_name").describe("Candidate base name.")
    string("version").describe("Candidate version.")
    string("sort").describe("Candidate sort.")
    requiredNullable("externDescription", string()).serialName("extern_description").describe("Candidate extern shape.")
    string("verdict").describe("Compatibility verdict.")
}
private val acceptedProvider = class_("WasmToolsAcceptedProvider") {
    string("dependencyPath").serialName("dependency_path").describe("Accepted provider path.")
}
private val rejectedDependency = class_("WasmToolsRejectedDependency") {
    string("dependencyPath").serialName("dependency_path").describe("Rejected dependency path.")
    string("reason").describe("Rejection reason.")
    string("message").describe("Rejection explanation.")
}
private val explicitCompatibility = class_("WasmToolsExplicitCompatibility") {
    boolean("compatible").describe("Whether all explicit dependencies match.")
    list("matches", of = dependencyMatch).describe("Matched imports and providers.")
    list("missingImports", of = missingImport).serialName("missing_imports").describe("Unmatched non-runtime imports.")
    list("issues", of = dependencyIssue).describe("Compatibility issues.")
    list("candidateProviders", of = candidateProvider).serialName("candidate_providers").describe("Inspected candidates.")
    list("acceptedProviders", of = acceptedProvider).serialName("accepted_providers").describe("Accepted provider paths.")
    list("rejectedDependencies", of = rejectedDependency).serialName("rejected_dependencies").describe("Rejected supplied paths.")
    int("directoryScanCount").serialName("directory_scan_count").describe("Always zero for explicit-only checking.")
}
private val dependencies = class_("WasmToolsDependencies") {
    string("stage").describe("`not_applicable`, `not_requested`, or `completed`.")
    list("paths", of = string()).describe("Explicit dependency paths supplied.")
    requiredNullable("compatibility", explicitCompatibility).describe("Explicit compatibility result, when requested.")
}
private val wit = class_("WasmToolsWitAvailability") {
    string("status").describe("Always `not_available`.")
    string("reason").describe("Why WIT source reconstruction is unavailable.")
}
val ComponentWitReportResultClass = class_("ComponentWitReport") {
    envelope()
    embed("component", of = componentSurface).describe("Component import and export surface.")
    embed("types", of = componentTypes).describe("Flat type graph and limits.")
    embed("dependencies", of = dependencies).describe("Explicit-only dependency evidence.")
    embed("wit", of = wit).describe("WIT reconstruction boundary.")
    outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.Pretty)
}

internal val WasmWorkflowTools: List<ToolSpec> = tools {
    tool(ValidateAndExplainToolId) {
        summary = "Report structural Wasm diagnostics, feature evidence, and explicit runtime compatibility."
        guidance = "Structural parsing only; `semantic_validation` is always `not_performed`."
        input = ValidateAndExplainInputClass
        output = ValidateAndExplainResultClass
    }
    tool(MetadataAuditToolId) {
        summary = "Audit Wasm custom sections and typed producers and target-features metadata."
        guidance = "Preserves encoded metadata occurrences and bounds custom-section previews to 96 bytes."
        input = workflowInput
        output = MetadataAuditResultClass
    }
    tool(ComponentWitReportToolId) {
        summary = "Report component surface, type graph, and explicit dependency compatibility."
        guidance = "Does not reconstruct WIT or discover dependency files; `wit.status` is `not_available`."
        input = ComponentWitReportInputClass
        output = ComponentWitReportResultClass
    }
}
