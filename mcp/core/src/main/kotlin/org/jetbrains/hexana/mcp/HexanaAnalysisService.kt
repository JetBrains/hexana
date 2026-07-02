package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.data.AuditDataPayloadPolicyResult
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
import org.jetbrains.hexana.mcp.model.data.ListDataSegmentsResult
import org.jetbrains.hexana.mcp.model.elements.ListElementSegmentsResult
import org.jetbrains.hexana.mcp.model.entrypoints.EntrypointPolicyResult
import org.jetbrains.hexana.mcp.model.artifacts.QueryArtifactsResult
import org.jetbrains.hexana.mcp.model.artifacts.PlanAbiBindingRegenerationResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryFunctionsResult
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.jetbrains.hexana.mcp.model.binaries.QueryBinaryResult
import org.jetbrains.hexana.mcp.model.components.CheckComponentCompatibilityResult
import org.jetbrains.hexana.mcp.model.components.ListComponentSurfaceResult
import org.jetbrains.hexana.mcp.model.components.ResolveComponentDependenciesResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.exports.SearchExportsResult
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.functions.AuditLocalStackBudgetResult
import org.jetbrains.hexana.mcp.model.functions.DisassembleFunctionResult
import org.jetbrains.hexana.mcp.model.functions.GetFunctionCallGraphResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.ListCodeSectionOffsetsResult
import org.jetbrains.hexana.mcp.model.functions.ResolveFunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.ResolveFunctionSymbolsResult
import org.jetbrains.hexana.mcp.model.functions.ResolveSourceLocationsResult
import org.jetbrains.hexana.mcp.model.functions.ResolveWasmFramesResult
import org.jetbrains.hexana.mcp.model.functions.SearchFunctionsByNameResult
import org.jetbrains.hexana.mcp.model.functions.SourceLocationInput
import org.jetbrains.hexana.mcp.model.functions.WasmFrameInput
import org.jetbrains.hexana.mcp.model.globals.AuditGlobalStatePolicyResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.memory.CheckMemoryContractResult
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.runtime.CheckRuntimeCompatibilityResult
import org.jetbrains.hexana.mcp.model.runtime.DiffRequiredProposalsResult
import org.jetbrains.hexana.mcp.model.runtime.ListRequiredProposalsResult
import org.jetbrains.hexana.mcp.model.sections.ListCustomSectionsResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.model.types.ListReferenceTypesResult
import org.jetbrains.hexana.mcp.model.types.ListValueTypesResult
import org.jetbrains.hexana.mcp.model.types.TypesResult
import org.jetbrains.hexana.mcp.query.*
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.extension

interface HexanaAnalysisService {
    fun loadBinary(input: BinaryInput): BinaryModuleIdentityResult {
        throw UnsupportedOperationException("loadBinary is not implemented")
    }

    fun summarizeBinary(input: BinaryInput): BinarySummaryResult {
        throw UnsupportedOperationException("summarizeBinary is not implemented")
    }

    fun queryBinary(
        input: BinaryInput,
        operation: String,
        entityTypes: List<String> = emptyList(),
        query: List<String>,
        offset: Int,
        limit: Int,
        detail: String,
        maxBytes: Int,
    ): QueryBinaryResult {
        throw UnsupportedOperationException("queryBinary is not implemented")
    }

    fun listBinaryFunctions(
        input: BinaryInput,
        offset: Int,
        limit: Int,
    ): BinaryFunctionsResult {
        throw UnsupportedOperationException("listBinaryFunctions is not implemented")
    }

    fun searchBinaryFunctions(
        input: BinaryInput,
        query: List<String>,
        offset: Int,
        limit: Int,
    ): BinaryFunctionsResult {
        throw UnsupportedOperationException("searchBinaryFunctions is not implemented")
    }

    fun queryArtifacts(
        input: WasmInput,
        baselineInput: WasmInput?,
        bindingInput: TextInput?,
        operation: String,
        entityTypes: List<String>,
        query: List<String>,
        startName: String?,
        startFuncidx: Int?,
        relations: List<String>,
        limit: Int,
        detail: String,
        maxBytes: Int
    ): QueryArtifactsResult {
        throw UnsupportedOperationException("queryArtifacts is not implemented")
    }

    fun planAbiBindingRegeneration(
        input: WasmInput,
        bindingInput: TextInput
    ): PlanAbiBindingRegenerationResult {
        throw UnsupportedOperationException("planAbiBindingRegeneration is not implemented")
    }

    fun getEntrypointPolicy(input: WasmInput): EntrypointPolicyResult {
        throw UnsupportedOperationException("getEntrypointPolicy is not implemented")
    }

    fun summarizeModule(input: WasmInput): SummarizeModuleResult
    fun listEnabledFeatures(input: WasmInput): ListEnabledFeaturesResult
    fun listRequiredProposals(input: WasmInput): ListRequiredProposalsResult {
        throw UnsupportedOperationException("listRequiredProposals is not implemented")
    }
    fun diffRequiredProposals(input: WasmInput, baselineInput: WasmInput): DiffRequiredProposalsResult {
        throw UnsupportedOperationException("diffRequiredProposals is not implemented")
    }
    fun checkRuntimeCompatibility(input: WasmInput, runtime: String, version: String?): CheckRuntimeCompatibilityResult {
        throw UnsupportedOperationException("checkRuntimeCompatibility is not implemented")
    }
    fun listComponentSurface(input: WasmInput): ListComponentSurfaceResult {
        throw UnsupportedOperationException("listComponentSurface is not implemented")
    }
    fun resolveComponentDependencies(
        input: WasmInput,
        projectRoot: String?,
        scanRoots: List<String>
    ): ResolveComponentDependenciesResult {
        throw UnsupportedOperationException("resolveComponentDependencies is not implemented")
    }
    fun checkComponentCompatibility(
        input: WasmInput,
        dependencyPaths: List<String>,
        projectRoot: String?,
        previousPath: String?,
        dependencyLockPath: String?
    ): CheckComponentCompatibilityResult {
        throw UnsupportedOperationException("checkComponentCompatibility is not implemented")
    }
    fun listCustomSections(input: WasmInput): ListCustomSectionsResult
    fun listImports(input: WasmInput): ListImportsResult
    fun listExports(input: WasmInput): ListExportsResult
    fun searchExports(input: WasmInput, query: List<String>, limit: Int): SearchExportsResult
    fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult
    fun listCodeSectionOffsets(input: WasmInput): ListCodeSectionOffsetsResult
    fun resolveFunctionSignature(input: WasmInput, funcidx: Int): ResolveFunctionSignatureResult
    fun disassembleFunction(input: WasmInput, funcidx: Int): DisassembleFunctionResult
    fun getFunctionCallGraph(input: WasmInput, indices: List<Int>): GetFunctionCallGraphResult
    fun searchFunctionsByName(input: WasmInput, query: String, limit: Int): SearchFunctionsByNameResult
    fun auditLocalStackBudget(
        input: WasmInput,
        policyInput: TextInput?,
        logInput: TextInput?,
        checkedExports: List<String>,
        maxLocals: Int?,
        countParams: Boolean
    ): AuditLocalStackBudgetResult {
        throw UnsupportedOperationException("auditLocalStackBudget is not implemented")
    }
    fun resolveWasmFrames(
        input: WasmInput,
        frames: List<WasmFrameInput>,
        sourceMapInput: TextInput?,
        crashReportInput: TextInput?,
    ): ResolveWasmFramesResult {
        throw UnsupportedOperationException("resolveWasmFrames is not implemented")
    }
    fun resolveFunctionSymbols(
        input: WasmInput,
        indices: List<Int>,
        sourceMapInput: TextInput?
    ): ResolveFunctionSymbolsResult {
        throw UnsupportedOperationException("resolveFunctionSymbols is not implemented")
    }
    fun resolveSourceLocations(
        input: WasmInput,
        locations: List<SourceLocationInput>,
        sourceMapInput: TextInput?
    ): ResolveSourceLocationsResult {
        throw UnsupportedOperationException("resolveSourceLocations is not implemented")
    }
    fun listExportedFunctions(input: WasmInput, entriesRange: IntRange): ListExportedFunctionsResult
    fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult
    fun listGlobals(input: WasmInput): ListGlobalsResult
    fun listTypes(input: WasmInput): TypesResult
    fun listValueTypes(input: WasmInput): ListValueTypesResult
    fun listReferenceTypes(input: WasmInput): ListReferenceTypesResult
    fun listMemory(input: WasmInput): ListMemoryResult
    fun checkMemoryContract(
        input: WasmInput,
        baselineInput: WasmInput,
        policyInput: TextInput,
        logInput: TextInput?
    ): CheckMemoryContractResult {
        throw UnsupportedOperationException("checkMemoryContract is not implemented")
    }
    fun auditGlobalStatePolicy(
        input: WasmInput,
        baselineInput: WasmInput,
        policyInput: TextInput,
        logInput: TextInput?,
        detail: String = "compact"
    ): AuditGlobalStatePolicyResult {
        throw UnsupportedOperationException("auditGlobalStatePolicy is not implemented")
    }
    fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult
    fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult
    fun listDataSegments(
        input: WasmInput,
        segmentStartInclusive: Int,
        segmentEndExclusive: Int,
        includeExports: Boolean,
        includeGlobals: Boolean,
        minByteLength: Int?
    ): ListDataSegmentsResult
    fun auditDataPayloadPolicy(
        input: WasmInput,
        policyInput: TextInput
    ): AuditDataPayloadPolicyResult {
        throw UnsupportedOperationException("auditDataPayloadPolicy is not implemented")
    }

    fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult
    fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult
    fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult
    fun getInstructionsForFunctions(input: WasmInput, indices: List<Int>): GetInstructionsForFunctionsResult
    fun listElementSegments(input: WasmInput): ListElementSegmentsResult
}

object HexanaAnalysisServices {
    val default: HexanaAnalysisService = DefaultHexanaAnalysisService()
}

class DefaultHexanaAnalysisService(
    private val maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    private val maxFileInputBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES,
    private val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
) : HexanaAnalysisService {

    override fun loadBinary(input: BinaryInput): BinaryModuleIdentityResult {
        return BinaryInputLoader(
            maxInlineInputBytes = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
            maxFileInputBytes = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
            allowedFilePolicy = allowedFilePolicy,
        ).load(input).identity
    }

    override fun summarizeBinary(input: BinaryInput): BinarySummaryResult {
        val binary = BinaryInputLoader(
            maxInlineInputBytes = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
            maxFileInputBytes = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
            allowedFilePolicy = allowedFilePolicy,
        ).load(input)
        return binary.provider.readBinarySummary(binary.identity.path)
    }

    override fun queryBinary(
        input: BinaryInput,
        operation: String,
        entityTypes: List<String>,
        query: List<String>,
        offset: Int,
        limit: Int,
        detail: String,
        maxBytes: Int,
    ): QueryBinaryResult {
        requireStringList(entityTypes, "Entity types")
        requireStringList(query, "Query")
        val binary = BinaryInputLoader(
            maxInlineInputBytes = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
            maxFileInputBytes = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
            allowedFilePolicy = allowedFilePolicy,
        ).load(input)
        return binary.provider.readBinaryQuery(
            operation = operation,
            entityTypes = entityTypes,
            query = query,
            offset = offset,
            limit = limit,
            detail = detail,
            maxBytes = maxBytes,
            pathLabel = binary.identity.path,
        )
    }

    override fun listBinaryFunctions(
        input: BinaryInput,
        offset: Int,
        limit: Int,
    ): BinaryFunctionsResult {
        val binary = BinaryInputLoader(
            maxInlineInputBytes = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
            maxFileInputBytes = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
            allowedFilePolicy = allowedFilePolicy,
        ).load(input)
        return binary.provider.readBinaryFunctions(
            offset = offset,
            limit = limit,
            pathLabel = binary.identity.path,
        )
    }

    override fun searchBinaryFunctions(
        input: BinaryInput,
        query: List<String>,
        offset: Int,
        limit: Int,
    ): BinaryFunctionsResult {
        requireStringList(query, "Query")
        val binary = BinaryInputLoader(
            maxInlineInputBytes = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
            maxFileInputBytes = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
            allowedFilePolicy = allowedFilePolicy,
        ).load(input)
        return binary.provider.searchBinaryFunctions(
            query = query,
            offset = offset,
            limit = limit,
            pathLabel = binary.identity.path,
        )
    }

    override fun queryArtifacts(
        input: WasmInput,
        baselineInput: WasmInput?,
        bindingInput: TextInput?,
        operation: String,
        entityTypes: List<String>,
        query: List<String>,
        startName: String?,
        startFuncidx: Int?,
        relations: List<String>,
        limit: Int,
        detail: String,
        maxBytes: Int
    ): QueryArtifactsResult {
        val wasm = load(input)
        val baseline = baselineInput?.let(::load)
        val binding = bindingInput?.let(::loadText)
        return wasm.provider.readArtifactQuery(
            baseline = baseline?.provider,
            bindingText = binding?.text,
            operation = operation,
            entityTypes = entityTypes,
            query = query,
            startName = startName,
            startFuncidx = startFuncidx,
            relations = relations,
            limit = limit,
            detail = detail,
            maxBytes = maxBytes,
            pathLabel = input.artifactPathLabel(),
            baselinePathLabel = baselineInput?.artifactPathLabel(),
            bindingPathLabel = bindingInput?.artifactPathLabel()
        )
    }

    override fun planAbiBindingRegeneration(
        input: WasmInput,
        bindingInput: TextInput
    ): PlanAbiBindingRegenerationResult {
        val wasm = load(input)
        val binding = loadText(bindingInput)
        return wasm.provider.planAbiBindingRegeneration(
            bindingText = binding.text,
            pathLabel = input.artifactPathLabel(),
            bindingPathLabel = bindingInput.artifactPathLabel()
        )
    }

    override fun getEntrypointPolicy(input: WasmInput): EntrypointPolicyResult {
        val wasm = load(input)
        return wasm.provider.readEntrypointPolicy(pathLabel = input.artifactPathLabel())
    }

    override fun summarizeModule(input: WasmInput): SummarizeModuleResult {
        val wasm = load(input)
        return wasm.provider.readModuleSummary()
    }

    override fun listEnabledFeatures(input: WasmInput): ListEnabledFeaturesResult {
        val wasm = load(input)
        return wasm.provider.readEnabledFeatures()
    }

    override fun listRequiredProposals(input: WasmInput): ListRequiredProposalsResult {
        val wasm = load(input)
        return wasm.provider.readRequiredProposals()
    }

    override fun diffRequiredProposals(input: WasmInput, baselineInput: WasmInput): DiffRequiredProposalsResult {
        val wasm = load(input)
        val baseline = load(baselineInput)
        return wasm.provider.diffRequiredProposals(
            baseline = baseline.provider,
            pathLabel = input.artifactPathLabel(),
            baselinePathLabel = baselineInput.artifactPathLabel(),
        )
    }

    override fun checkRuntimeCompatibility(
        input: WasmInput,
        runtime: String,
        version: String?
    ): CheckRuntimeCompatibilityResult {
        requireNonBlank(runtime, "Runtime")
        val wasm = load(input)
        return wasm.provider.readRuntimeCompatibility(runtime, version)
    }

    override fun listComponentSurface(input: WasmInput): ListComponentSurfaceResult {
        val wasm = try {
            loadComponent(input)
        } catch (error: HexanaAnalysisException) {
            if (error.code != HexanaAnalysisErrorCode.WASM_PARSE_FAILED) throw error
            return ListComponentSurfaceResult(
                path = input.artifactPathLabel(),
                isComponent = false,
                imports = emptyList(),
                exports = emptyList(),
                summary = org.jetbrains.hexana.mcp.model.components.ComponentSurfaceSummaryResult(
                    importCount = 0,
                    exportCount = 0,
                    nonRuntimeImportCount = 0,
                    runtimeImportCount = 0,
                ),
            )
        }
        return wasm.provider.readComponentSurface(input.artifactPathLabel())
    }

    override fun resolveComponentDependencies(
        input: WasmInput,
        projectRoot: String?,
        scanRoots: List<String>
    ): ResolveComponentDependenciesResult {
        requireStringList(scanRoots, "Scan roots")
        val wasm = loadComponent(input)
        return wasm.provider.resolveComponentDependencies(
            path = input.artifactPathLabel(),
            projectRoot = projectRoot,
            scanRoots = scanRoots,
            allowedFilePolicy = allowedFilePolicy,
        )
    }

    override fun checkComponentCompatibility(
        input: WasmInput,
        dependencyPaths: List<String>,
        projectRoot: String?,
        previousPath: String?,
        dependencyLockPath: String?
    ): CheckComponentCompatibilityResult {
        requireStringList(dependencyPaths, "Dependency paths")
        val wasm = loadComponent(input)
        return wasm.provider.checkComponentCompatibility(
            path = input.artifactPathLabel(),
            dependencyPaths = dependencyPaths,
            projectRoot = projectRoot,
            previousPath = previousPath,
            dependencyLockPath = dependencyLockPath,
            allowedFilePolicy = allowedFilePolicy,
        )
    }

    override fun listCustomSections(input: WasmInput): ListCustomSectionsResult {
        val wasm = load(input)
        return wasm.provider.readCustomSections()
    }

    override fun listImports(input: WasmInput): ListImportsResult {
        val wasm = load(input)
        return wasm.provider.readImportFunctions()
    }

    override fun listExports(input: WasmInput): ListExportsResult {
        val wasm = load(input)
        return wasm.provider.readExports()
    }

    override fun searchExports(input: WasmInput, query: List<String>, limit: Int): SearchExportsResult {
        requireNonEmptyStrings(query, "Query")
        requireLimit(limit)
        val wasm = load(input)
        return wasm.provider.readExportsByName(query, limit)
    }

    override fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult {
        requireOffset(offset)
        requireLimit(limit)
        val wasm = load(input)
        return wasm.provider.readFunctions(offset, limit)
    }

    override fun listCodeSectionOffsets(input: WasmInput): ListCodeSectionOffsetsResult {
        val wasm = load(input)
        return wasm.provider.readCodeSectionOffsets()
    }

    override fun resolveFunctionSignature(input: WasmInput, funcidx: Int): ResolveFunctionSignatureResult {
        requireIndices(listOf(funcidx))
        val wasm = load(input)
        return wasm.provider.readFunctionSignature(funcidx)
    }

    override fun disassembleFunction(input: WasmInput, funcidx: Int): DisassembleFunctionResult {
        requireIndices(listOf(funcidx))
        val wasm = load(input)
        return wasm.provider.readFunctionDisassembly(funcidx)
    }

    override fun getFunctionCallGraph(input: WasmInput, indices: List<Int>): GetFunctionCallGraphResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readFunctionCallGraph(indices)
    }

    override fun searchFunctionsByName(input: WasmInput, query: String, limit: Int): SearchFunctionsByNameResult {
        requireNonBlank(query, "Query")
        requireLimit(limit)
        val wasm = load(input)
        return wasm.provider.readFunctionsByName(query, limit)
    }

    override fun auditLocalStackBudget(
        input: WasmInput,
        policyInput: TextInput?,
        logInput: TextInput?,
        checkedExports: List<String>,
        maxLocals: Int?,
        countParams: Boolean
    ): AuditLocalStackBudgetResult {
        requireStringList(checkedExports, "Checked exports")
        maxLocals?.let { requireNonNegative(it, "Maximum locals") }
        val wasm = load(input)
        val policy = policyInput?.let(::loadText)
        val log = logInput?.let(::loadText)
        return wasm.provider.auditLocalStackBudget(
            policyText = policy?.text,
            logText = log?.text,
            checkedExports = checkedExports,
            maxLocals = maxLocals,
            countParams = countParams,
            pathLabel = input.projectRelativeArtifactPathLabel(),
            policyPathLabel = policyInput?.artifactPathLabel(),
            logPathLabel = logInput?.artifactPathLabel()
        )
    }

    override fun resolveWasmFrames(
        input: WasmInput,
        frames: List<WasmFrameInput>,
        sourceMapInput: TextInput?,
        crashReportInput: TextInput?,
    ): ResolveWasmFramesResult {
        if (frames.isEmpty() && crashReportInput == null) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "Either frames or crashReportPath must be provided"
            )
        }
        requireFrameInputs(frames)
        val wasm = load(input)
        val sourceMap = loadSourceMapText(input, sourceMapInput)
        val crashReport = crashReportInput?.let(::loadText)
        return wasm.provider.resolveWasmFrames(
            frames = frames,
            sourceMapText = sourceMap?.text,
            sourceMapPath = sourceMap?.path,
            crashReportText = crashReport?.text,
            crashReportPath = crashReportInput?.artifactPathLabel(),
        )
    }

    override fun resolveFunctionSymbols(
        input: WasmInput,
        indices: List<Int>,
        sourceMapInput: TextInput?
    ): ResolveFunctionSymbolsResult {
        requireIndices(indices)
        val wasm = load(input)
        val sourceMap = sourceMapInput?.let(::loadText)
        return wasm.provider.resolveFunctionSymbols(
            indices = indices,
            sourceMapText = sourceMap?.text,
            sourceMapPath = sourceMapInput?.artifactPathLabel(),
        )
    }

    override fun resolveSourceLocations(
        input: WasmInput,
        locations: List<SourceLocationInput>,
        sourceMapInput: TextInput?
    ): ResolveSourceLocationsResult {
        requireSourceLocationInputs(locations)
        val wasm = load(input)
        val sourceMap = sourceMapInput?.let(::loadText)
        return wasm.provider.resolveSourceLocations(
            locations = locations,
            sourceMapText = sourceMap?.text,
            sourceMapPath = sourceMapInput?.artifactPathLabel(),
        )
    }

    override fun listExportedFunctions(input: WasmInput, entriesRange: IntRange): ListExportedFunctionsResult {
        requireClosedRange(entriesRange)
        val wasm = load(input)
        return wasm.provider.readExportedFunctions(entriesRange)
    }

    override fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readFunctionsByIndices(indices)
    }

    override fun listGlobals(input: WasmInput): ListGlobalsResult {
        val wasm = load(input)
        return wasm.provider.readGlobals()
    }

    override fun listTypes(input: WasmInput): TypesResult {
        val wasm = load(input)
        return wasm.provider.readTypes()
    }

    override fun listValueTypes(input: WasmInput): ListValueTypesResult {
        val wasm = load(input)
        return wasm.provider.readValueTypes()
    }

    override fun listReferenceTypes(input: WasmInput): ListReferenceTypesResult {
        val wasm = load(input)
        return wasm.provider.readReferenceTypes()
    }

    override fun listMemory(input: WasmInput): ListMemoryResult {
        val wasm = load(input)
        return wasm.provider.readMemory()
    }

    override fun checkMemoryContract(
        input: WasmInput,
        baselineInput: WasmInput,
        policyInput: TextInput,
        logInput: TextInput?
    ): CheckMemoryContractResult {
        val wasm = load(input)
        val baseline = load(baselineInput)
        val policy = loadText(policyInput)
        val log = logInput?.let(::loadText)
        return wasm.provider.checkMemoryContract(
            baseline = baseline.provider,
            policyText = policy.text,
            logText = log?.text,
            pathLabel = input.artifactPathLabel(),
            baselinePathLabel = baselineInput.artifactPathLabel(),
            policyPathLabel = policyInput.artifactPathLabel(),
            logPathLabel = logInput?.artifactPathLabel()
        )
    }

    override fun auditGlobalStatePolicy(
        input: WasmInput,
        baselineInput: WasmInput,
        policyInput: TextInput,
        logInput: TextInput?,
        detail: String
    ): AuditGlobalStatePolicyResult {
        val wasm = load(input)
        val baseline = load(baselineInput)
        val policy = loadText(policyInput)
        val log = logInput?.let(::loadText)
        return wasm.provider.auditGlobalStatePolicy(
            baseline = baseline.provider,
            policyText = policy.text,
            logText = log?.text,
            pathLabel = input.artifactPathLabel(),
            baselinePathLabel = baselineInput.artifactPathLabel(),
            policyPathLabel = policyInput.artifactPathLabel(),
            logPathLabel = logInput?.artifactPathLabel(),
            detail = detail
        )
    }

    override fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readGlobalsByIndices(indices)
    }

    override fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult {
        requireClosedRange(entriesRange)
        val wasm = load(input)
        return wasm.provider.readDataSections(entriesRange)
    }

    override fun listDataSegments(
        input: WasmInput,
        segmentStartInclusive: Int,
        segmentEndExclusive: Int,
        includeExports: Boolean,
        includeGlobals: Boolean,
        minByteLength: Int?
    ): ListDataSegmentsResult {
        requireHalfOpenRange(segmentStartInclusive, segmentEndExclusive)
        requireOptionalNonNegative(minByteLength, "Minimum byte length")
        val wasm = load(input)
        return wasm.provider.readDataSegments(
            segmentStartInclusive = segmentStartInclusive,
            segmentEndExclusive = segmentEndExclusive,
            includeExports = includeExports,
            includeGlobals = includeGlobals,
            minByteLength = minByteLength
        )
    }

    override fun auditDataPayloadPolicy(input: WasmInput, policyInput: TextInput): AuditDataPayloadPolicyResult {
        val wasm = load(input)
        val policy = loadText(policyInput)
        return wasm.provider.auditDataPayloadPolicy(
            policyText = policy.text,
            pathLabel = input.projectRelativeArtifactPathLabel(),
            policyPathLabel = policy.path ?: policyInput.artifactPathLabel()
        )
    }

    override fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readMemoryByIndices(indices)
    }

    override fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readTypesByIndices(indices)
    }

    override fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readLocalsForFunctions(indices)
    }

    override fun getInstructionsForFunctions(input: WasmInput, indices: List<Int>): GetInstructionsForFunctionsResult {
        requireIndices(indices)
        val wasm = load(input)
        return wasm.provider.readInstructionsForFunctions(indices)
    }

    override fun listElementSegments(input: WasmInput): ListElementSegmentsResult {
        val wasm = load(input)
        return wasm.provider.readElementSegments()
    }

    private fun load(input: WasmInput): LoadedWasm {
        return when (input) {
            is WasmInput.FilePath -> loadFromPath(input.path)
            is WasmInput.Uri -> throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND,
                "Unsupported input kind: uri"
            )

            is WasmInput.InlineBytes -> loadFromInlineBytes(input.base64)
            is WasmInput.RawBytes -> loadFromRawBytes(input.bytes)
        }
    }

    private fun loadComponent(input: WasmInput): LoadedComponentWasm {
        return when (input) {
            is WasmInput.FilePath -> loadComponentFromPath(input.path)
            is WasmInput.Uri -> throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND,
                "Unsupported input kind: uri"
            )

            is WasmInput.InlineBytes -> loadComponentFromInlineBytes(input.base64)
            is WasmInput.RawBytes -> loadComponentFromRawBytes(input.bytes)
        }
    }

    private fun loadText(input: TextInput): LoadedText {
        return when (input) {
            is TextInput.FilePath -> loadTextFromPath(input.path, input.path)
        }
    }

    private fun loadTextFromPath(rawPath: String, pathLabel: String? = rawPath): LoadedText {
        val path = allowedFilePolicy.resolveAllowedRegularFile(rawPath, "Text file")
        val size = try {
            Files.size(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read text file",
                error
            )
        }
        if (size > 1_000_000L) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "Text file exceeds maximum size of 1000000 bytes"
            )
        }
        val text = try {
            Files.readString(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read text file",
                error
            )
        }
        return LoadedText(text, pathLabel)
    }

    private fun loadSourceMapText(input: WasmInput, explicitInput: TextInput?): LoadedText? {
        if (explicitInput != null) return loadText(explicitInput)
        val wasmPath = (input as? WasmInput.FilePath)?.path ?: return null
        val sourceMapPath = try {
            Path.of("$wasmPath.map")
        } catch (_: Exception) {
            return null
        }
        if (!Files.isRegularFile(sourceMapPath)) return null
        return loadTextFromPath(sourceMapPath.toString(), sourceMapPath.toString())
    }

    private fun loadFromPath(rawPath: String): LoadedWasm {
        val path = allowedFilePolicy.resolveAllowedRegularFile(rawPath, ".wasm file")
        if (path.extension.lowercase() != "wasm") {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.NOT_WASM, "Not a .wasm file")
        }
        requireFileByteCount(path)

        val bytes = try {
            Files.readAllBytes(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read .wasm file",
                error
            )
        }

        return loadFromBytes(bytes)
    }

    private fun loadComponentFromPath(rawPath: String): LoadedComponentWasm {
        val path = allowedFilePolicy.resolveAllowedRegularFile(rawPath, ".wasm file")
        if (path.extension.lowercase() != "wasm") {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.NOT_WASM, "Not a .wasm file")
        }
        requireFileByteCount(path)

        val bytes = try {
            Files.readAllBytes(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read .wasm file",
                error
            )
        }

        return loadComponentFromBytes(bytes)
    }

    private fun loadFromInlineBytes(base64: String): LoadedWasm {
        if (base64.isBlank()) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Inline bytes must not be blank")
        }
        requireInlineBase64Size(base64)

        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "Inline bytes must be valid base64"
            )
        }

        requireInlineByteCount(bytes.size)
        return loadFromBytes(bytes)
    }

    private fun loadComponentFromInlineBytes(base64: String): LoadedComponentWasm {
        if (base64.isBlank()) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Inline bytes must not be blank")
        }
        requireInlineBase64Size(base64)

        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "Inline bytes must be valid base64"
            )
        }

        requireInlineByteCount(bytes.size)
        return loadComponentFromBytes(bytes)
    }

    private fun loadFromRawBytes(bytes: ByteArray): LoadedWasm {
        requireInlineByteCount(bytes.size)
        return loadFromBytes(bytes)
    }

    private fun loadComponentFromRawBytes(bytes: ByteArray): LoadedComponentWasm {
        requireInlineByteCount(bytes.size)
        return loadComponentFromBytes(bytes)
    }

    private fun loadFromBytes(bytes: ByteArray): LoadedWasm {
        return LoadedWasm(bytes.toCommonByteBuffer().loadCoreProviderOrThrow())
    }

    private fun loadComponentFromBytes(bytes: ByteArray): LoadedComponentWasm {
        return LoadedComponentWasm(bytes.toCommonByteBuffer().loadComponentProviderOrThrow())
    }

    private fun requireInlineBase64Size(base64: String) {
        val paddingBytes = base64.takeLastWhile { it == '=' }.length.coerceAtMost(2)
        val estimatedDecodedSize = ((base64.length.toLong() + 3L) / 4L) * 3L - paddingBytes
        if (estimatedDecodedSize > maxInlineInputBytes.toLong()) {
            throw inlineBytesTooLarge()
        }
    }

    private fun requireFileByteCount(path: Path) {
        val size = try {
            Files.size(path)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read .wasm file",
                error
            )
        }
        if (size > maxFileInputBytes.toLong()) {
            throw fileTooLarge()
        }
    }
    private fun requireInlineByteCount(size: Int) {
        if (size > maxInlineInputBytes) {
            throw inlineBytesTooLarge()
        }
    }

    private fun fileTooLarge(): HexanaAnalysisException {
        return HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "File exceeds maximum size of $maxFileInputBytes bytes"
        )
    }
    private fun inlineBytesTooLarge(): HexanaAnalysisException {
        return HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "Inline bytes exceed maximum size of $maxInlineInputBytes bytes"
        )
    }
    private fun requireOffset(offset: Int) {
        if (offset < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Offset must be greater than or equal to 0"
            )
        }
    }

    private fun requireLimit(limit: Int) {
        if (limit <= 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Limit must be greater than 0"
            )
        }
    }

    private fun requireNonBlank(value: String, label: String) {
        if (value.isBlank()) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$label must not be blank"
            )
        }
    }

    private fun requireNonEmptyStrings(values: List<String>, label: String) {
        if (values.isEmpty()) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$label must contain at least one value"
            )
        }
        if (values.any { it.isBlank() }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$label values must not be blank"
            )
        }
    }

    private fun requireStringList(values: List<String>, label: String) {
        if (values.any { it.isBlank() }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$label values must not be blank"
            )
        }
    }

    private fun requireClosedRange(range: IntRange) {
        if (range.first < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `from` must be greater than or equal to 0"
            )
        }
        if (range.last < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `to` must be greater than or equal to 0"
            )
        }
        if (range.last < range.first) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `to` must be greater than or equal to `from`"
            )
        }
    }

    private fun requireHalfOpenRange(from: Int, to: Int) {
        if (from < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `from` must be greater than or equal to 0"
            )
        }
        if (to < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `to` must be greater than or equal to 0"
            )
        }
        if (to < from) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Range `to` must be greater than or equal to `from`"
            )
        }
    }

    private fun requireIndices(indices: List<Int>) {
        if (indices.any { it < 0 }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Indices must be greater than or equal to 0"
            )
        }
    }

    private fun requireFrameInputs(frames: List<WasmFrameInput>) {
        if (frames.any { it.funcidx < 0 }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Frame funcidx values must be greater than or equal to 0"
            )
        }
        if (frames.any { it.codeOffset != null && it.codeOffset < 0 }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Frame code offsets must be greater than or equal to 0"
            )
        }
    }

    private fun requireSourceLocationInputs(locations: List<SourceLocationInput>) {
        if (locations.any { it.funcidx != null && it.funcidx < 0 }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Location funcidx values must be greater than or equal to 0"
            )
        }
        if (locations.any { it.codeOffset != null && it.codeOffset < 0 }) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "Location code offsets must be greater than or equal to 0"
            )
        }
    }

    private fun requireOptionalNonNegative(value: Int?, label: String) {
        if (value != null && value < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "$label must be greater than or equal to 0"
            )
        }
    }

    private fun requireNonNegative(value: Int, label: String) {
        if (value < 0) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.OUT_OF_RANGE,
                "$label must be greater than or equal to 0"
            )
        }
    }

    private fun WasmInput.artifactPathLabel(): String? =
        (this as? WasmInput.FilePath)?.path

    private fun TextInput.artifactPathLabel(): String? =
        (this as? TextInput.FilePath)?.path

    private fun WasmInput.projectRelativeArtifactPathLabel(): String? {
        val label = artifactPathLabel() ?: return null
        val path = try {
            Path.of(label)
        } catch (_: Exception) {
            return label
        }
        if (!path.isAbsolute) return label

        val normalizedPath = path.normalize()
        val projectRoot = nearestProjectRoot(normalizedPath) ?: return label
        return try {
            projectRoot.relativize(normalizedPath).toString().replace(File.separatorChar, '/')
        } catch (_: Exception) {
            label
        }
    }

    private fun nearestProjectRoot(path: Path): Path? {
        var directory = if (Files.isDirectory(path)) path else path.parent
        while (directory != null) {
            if (Files.exists(directory.resolve(".git"))) return directory
            directory = directory.parent
        }
        return null
    }

    private data class LoadedWasm(val provider: WasmBinaryProvider)
    private data class LoadedComponentWasm(val provider: WasmComponentBinaryProvider)
    private data class LoadedText(val text: String, val path: String? = null)

    private fun CommonByteBuffer.loadCoreProviderOrThrow(): WasmBinaryProvider {
        if (WasmBinaryProviderSelector.select(this) !is BinaryProviderType.Wasm) {
            throw wasmParseFailed()
        }

        return WasmBinaryProvider(this).takeIf(WasmBinaryProvider::canProvideWasmModule)
            ?: throw wasmParseFailed()
    }

    private fun CommonByteBuffer.loadComponentProviderOrThrow(): WasmComponentBinaryProvider {
        if (WasmBinaryProviderSelector.select(this) != BinaryProviderType.WasmComponent) {
            throw wasmParseFailed()
        }

        return WasmComponentBinaryProvider(this).takeIf(WasmComponentBinaryProvider::canProvideWasmModule)
            ?: throw wasmParseFailed()
    }

    private fun wasmParseFailed(): HexanaAnalysisException {
        return HexanaAnalysisException(
            HexanaAnalysisErrorCode.WASM_PARSE_FAILED,
            "Failed to parse .wasm file"
        )
    }
}
