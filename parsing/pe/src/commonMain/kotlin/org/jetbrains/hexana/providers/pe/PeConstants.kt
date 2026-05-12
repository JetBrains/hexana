package org.jetbrains.hexana.providers.pe

object PeDataDirectoryIndex {
    const val Export: Int = 0
    const val Import: Int = 1
    const val Resource: Int = 2
    const val Exception: Int = 3
    const val Certificate: Int = 4
    const val BaseRelocation: Int = 5
    const val Debug: Int = 6
    const val Architecture: Int = 7
    const val GlobalPtr: Int = 8
    const val Tls: Int = 9
    const val LoadConfig: Int = 10
    const val BoundImport: Int = 11
    const val ImportAddressTable: Int = 12
    const val DelayImport: Int = 13
    const val ClrRuntimeHeader: Int = 14

    val knownIndices: Set<Int> = setOf(
        Export,
        Import,
        Resource,
        Exception,
        Certificate,
        BaseRelocation,
        Debug,
        Architecture,
        GlobalPtr,
        Tls,
        LoadConfig,
        BoundImport,
        ImportAddressTable,
        DelayImport,
        ClrRuntimeHeader,
    )
}
