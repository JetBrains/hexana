-keep class org.jetbrains.hexana.cli.hexanacli.MainKt {
    public static void main(java.lang.String[]);
}

-keep class org.jetbrains.hexana.cli.hexanacliscript.MainKt {
    public static void main(java.lang.String[]);
}

-allowaccessmodification
-repackageclasses obf

-keep class org.jetbrains.hexana.cli.runtime.** { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaCliInputMode { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaCliToolGroup { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaCliOutputPolicy { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaCliExecutionStatus { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaCliToolSpec { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliToolRegistry { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaCliReleaseProduct { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaCliProductToolSelection { *; }
-keep class org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliProducts { *; }

-keep class org.jetbrains.hexana.mcp.* { *; }

-keep,allowoptimization @kotlinx.serialization.Serializable class org.jetbrains.hexana.mcp.model.** { *; }
-keep,allowoptimization class org.jetbrains.hexana.mcp.model.**$$serializer { *; }
-keep,allowoptimization class org.jetbrains.hexana.mcp.model.**$Companion { *; }
-keep,allowoptimization class org.jetbrains.hexana.mcp.model.data.Base64ByteArraySerializer { *; }

-keep,allowoptimization,allowobfuscation class org.jetbrains.hexana.wasm.components.** { *; }

-keepattributes Signature,InnerClasses,EnclosingMethod
