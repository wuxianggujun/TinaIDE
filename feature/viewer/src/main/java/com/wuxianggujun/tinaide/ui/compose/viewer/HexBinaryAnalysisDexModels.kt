package com.wuxianggujun.tinaide.ui.compose.viewer

/**
 * DEX analysis domain models.
 */

internal data class HexDexSummary(
    val version: String,
    val checksum: Long,
    val signatureHex: String,
    val fileSizeFromHeader: Long,
    val headerSize: Long,
    val endianTag: Long,
    val mapOffset: Long,
    val stringIdsSize: Int,
    val stringIdsOffset: Long,
    val typeIdsSize: Int,
    val typeIdsOffset: Long,
    val protoIdsSize: Int,
    val protoIdsOffset: Long,
    val fieldIdsSize: Int,
    val fieldIdsOffset: Long,
    val methodIdsSize: Int,
    val methodIdsOffset: Long,
    val classDefsSize: Int,
    val classDefsOffset: Long,
    val dataSize: Long,
    val dataOffset: Long,
    val stringEntries: List<HexDexStringEntry> = emptyList(),
    val typeEntries: List<HexDexTypeEntry> = emptyList(),
    val protoEntries: List<HexDexProtoEntry> = emptyList(),
    val fieldEntries: List<HexDexFieldEntry> = emptyList(),
    val methodEntries: List<HexDexMethodEntry> = emptyList(),
    val classDefEntries: List<HexDexClassDefEntry> = emptyList(),
    val classDataMethodEntries: List<HexDexClassDataMethodEntry> = emptyList(),
    val codeItemEntries: List<HexDexCodeItemEntry> = emptyList(),
    val callReferenceEntries: List<HexDexCallReferenceEntry> = emptyList(),
    val stringReferenceEntries: List<HexDexStringReferenceEntry> = emptyList(),
    val fieldReferenceEntries: List<HexDexFieldReferenceEntry> = emptyList(),
    val mapEntries: List<HexDexMapEntry> = emptyList()
) {
    val nativeMethodCount: Int
        get() = classDataMethodEntries.count { entry ->
            entry.executionKind == HexDexClassDataMethodExecutionKind.NATIVE
        }
}

internal data class HexDexStringEntry(
    val index: Int,
    val stringIdOffset: Long,
    val dataOffset: Long,
    val value: String
)

internal data class HexDexTypeEntry(
    val index: Int,
    val typeIdOffset: Long,
    val descriptorStringIndex: Long,
    val descriptor: String
)

internal data class HexDexProtoEntry(
    val index: Int,
    val protoIdOffset: Long,
    val shortyStringIndex: Long,
    val shorty: String,
    val returnTypeIndex: Long,
    val returnTypeDescriptor: String,
    val parametersOffset: Long,
    val parameterTypeDescriptors: List<String>,
    val signature: String
)

internal data class HexDexFieldEntry(
    val index: Int,
    val fieldIdOffset: Long,
    val classIndex: Int,
    val classDescriptor: String,
    val typeIndex: Int,
    val typeDescriptor: String,
    val nameStringIndex: Long,
    val name: String
)

internal data class HexDexMethodEntry(
    val index: Int,
    val methodIdOffset: Long,
    val classIndex: Int,
    val classDescriptor: String,
    val protoIndex: Int,
    val protoShorty: String,
    val protoSignature: String,
    val nameStringIndex: Long,
    val name: String
)

internal data class HexDexClassDefEntry(
    val index: Int,
    val classDefOffset: Long,
    val classIndex: Long,
    val classDescriptor: String,
    val accessFlags: Long,
    val superclassIndex: Long?,
    val superclassDescriptor: String?,
    val interfacesOffset: Long,
    val sourceFileIndex: Long?,
    val sourceFile: String?,
    val annotationsOffset: Long,
    val classDataOffset: Long,
    val staticValuesOffset: Long
)

internal data class HexDexClassDataMethodEntry(
    val index: Int,
    val classDefIndex: Int,
    val classDescriptor: String,
    val kind: HexDexClassDataMethodKind,
    val methodIndex: Long,
    val methodName: String,
    val methodClassDescriptor: String,
    val protoSignature: String,
    val accessFlags: Long,
    val classDataOffset: Long,
    val entryOffset: Long,
    val codeOffset: Long,
    val executionKind: HexDexClassDataMethodExecutionKind = dexClassDataMethodExecutionKind(
        accessFlags = accessFlags,
        codeOffset = codeOffset
    )
)

internal enum class HexDexClassDataMethodKind {
    DIRECT,
    VIRTUAL
}

internal enum class HexDexClassDataMethodExecutionKind {
    CODE,
    NATIVE,
    ABSTRACT,
    NO_CODE
}

internal data class HexDexCodeItemEntry(
    val index: Int,
    val methodIndex: Long,
    val methodName: String,
    val methodClassDescriptor: String,
    val protoSignature: String,
    val codeOffset: Long,
    val registersSize: Int,
    val insSize: Int,
    val outsSize: Int,
    val triesSize: Int,
    val debugInfoOffset: Long,
    val insnsSize: Long,
    val firstOpcode: Int,
    val firstOpcodeName: String,
    val previewCodeUnitsHex: String
)

internal data class HexDexCallReferenceEntry(
    val index: Int,
    val callerMethodIndex: Long,
    val callerClassDescriptor: String,
    val callerMethodName: String,
    val callerProtoSignature: String,
    val targetMethodIndex: Long,
    val targetClassDescriptor: String,
    val targetMethodName: String,
    val targetProtoSignature: String,
    val opcode: Int,
    val opcodeName: String,
    val instructionOffset: Long,
    val codeOffset: Long,
    val targetMethodIdOffset: Long?
)

internal data class HexDexStringReferenceEntry(
    val index: Int,
    val callerMethodIndex: Long,
    val callerClassDescriptor: String,
    val callerMethodName: String,
    val callerProtoSignature: String,
    val stringIndex: Long,
    val value: String,
    val opcode: Int,
    val opcodeName: String,
    val instructionOffset: Long,
    val codeOffset: Long,
    val stringIdOffset: Long?,
    val stringDataOffset: Long?
)

internal data class HexDexFieldReferenceEntry(
    val index: Int,
    val callerMethodIndex: Long,
    val callerClassDescriptor: String,
    val callerMethodName: String,
    val callerProtoSignature: String,
    val fieldIndex: Long,
    val fieldClassDescriptor: String,
    val fieldName: String,
    val fieldTypeDescriptor: String,
    val opcode: Int,
    val opcodeName: String,
    val instructionOffset: Long,
    val codeOffset: Long,
    val fieldIdOffset: Long?
)

internal data class HexDexMapEntry(
    val index: Int,
    val type: Int,
    val typeName: String,
    val size: Long,
    val offset: Long,
    val entryFileOffset: Long
)

internal enum class DexMapEntryFilter {
    ALL,
    IDS,
    CLASS_DATA,
    CODE,
    DATA
}
