-keep class org.jetbrains.hexana.mcp.server.MainKt {
    public static void main(java.lang.String[]);
}

-keep class org.jetbrains.hexana.mcp.server.HexanaLogLevel { *; }

-allowaccessmodification
-repackageclasses obf

-keep,allowoptimization @kotlinx.serialization.Serializable class org.jetbrains.hexana.mcp.model.** { *; }
-keep,allowoptimization class org.jetbrains.hexana.mcp.model.**$$serializer { *; }
-keep,allowoptimization class org.jetbrains.hexana.mcp.model.**$Companion { *; }
-keep,allowoptimization class org.jetbrains.hexana.mcp.model.data.Base64ByteArraySerializer { *; }

-keepattributes Signature,InnerClasses,EnclosingMethod
