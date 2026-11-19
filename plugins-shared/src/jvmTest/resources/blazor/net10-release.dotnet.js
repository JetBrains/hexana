// Boot manifest of a `dotnet new blazorwasm` publish (.NET 10.0.403 SDK), as dotnet.<fp>.js embeds it; the JS around it is cut.
export const config = /*json-start*/{
  "mainAssemblyName": "app",
  "resources": {
    "hash": "sha256-40EBI6pK0/IGXiCFBM1HDiUCOaJGyd+LQYmEdL6eirc=",
    "jsModuleNative": [
      {
        "name": "dotnet.native.bjoj0wmylw.js"
      }
    ],
    "jsModuleRuntime": [
      {
        "name": "dotnet.runtime.5nc8i5dl74.js"
      }
    ],
    "wasmNative": [
      {
        "name": "dotnet.native.a6k1euv12n.wasm",
        "hash": "sha256-YlIWUrqwAiQuAJNBOX+Zm9wpWL+sF3J449iFq7JHouQ=",
        "cache": "force-cache"
      }
    ],
    "icu": [
      {
        "virtualPath": "icudt_CJK.dat",
        "name": "icudt_CJK.tjcz0u77k5.dat",
        "hash": "sha256-SZLtQnRc0JkwqHab0VUVP7T3uBPSeYzxzDnpxPpUnHk=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "icudt_EFIGS.dat",
        "name": "icudt_EFIGS.tptq2av103.dat",
        "hash": "sha256-8fItetYY8kQ0ww6oxwTLiT3oXlBwHKumbeP2pRF4yTc=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "icudt_no_CJK.dat",
        "name": "icudt_no_CJK.lfu7j35m59.dat",
        "hash": "sha256-L7sV7NEYP37/Qr2FPCePo5cJqRgTXRwGHuwF5Q+0Nfs=",
        "cache": "force-cache"
      }
    ],
    "coreAssembly": [
      {
        "virtualPath": "System.Runtime.InteropServices.JavaScript.wasm",
        "name": "System.Runtime.InteropServices.JavaScript.qba5uzr262.wasm",
        "hash": "sha256-hj26LVfbFpoVPyXTjAXdVk41FKNiFwr2eGNynxIaSNQ=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Private.CoreLib.wasm",
        "name": "System.Private.CoreLib.nyy2te4ckx.wasm",
        "hash": "sha256-gW0yMtHAh1+VjoTuYjwJpu3txz7n86TDoBxpBnVk9XE=",
        "cache": "force-cache"
      }
    ],
    "assembly": [
      {
        "virtualPath": "Microsoft.AspNetCore.Components.wasm",
        "name": "Microsoft.AspNetCore.Components.sicant1cet.wasm",
        "hash": "sha256-5Hj1YhjrCBNHkthxfBFjEBsGb1aWcKpeL1Bm5LUR4iE=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.AspNetCore.Components.Web.wasm",
        "name": "Microsoft.AspNetCore.Components.Web.b7njz8u6cx.wasm",
        "hash": "sha256-N39N8zYgqDE0bWdoPrtPeW24V6ByqCXZlEnf4Urue7E=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.AspNetCore.Components.WebAssembly.wasm",
        "name": "Microsoft.AspNetCore.Components.WebAssembly.679vrjpzc0.wasm",
        "hash": "sha256-rkiUe37ArqLeP7Redy6oMvS5MKGpA9YEi8Mu+92WxUM=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.Configuration.wasm",
        "name": "Microsoft.Extensions.Configuration.rgjmp347zf.wasm",
        "hash": "sha256-C7GPXPXoVldDMNqp7glnbWRY3LqQV22sIZt68+o3YTM=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.Configuration.Abstractions.wasm",
        "name": "Microsoft.Extensions.Configuration.Abstractions.wslsrredsq.wasm",
        "hash": "sha256-YMQXBk4jrVc9OaTp+/KtO0wrrkF0KurH384yJ5BMY6o=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.Configuration.Json.wasm",
        "name": "Microsoft.Extensions.Configuration.Json.dmmcgk01r0.wasm",
        "hash": "sha256-hR3hJyllFhcAJODYJ4O/15D7zPKt4rm31ndgrYDdAXM=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.DependencyInjection.wasm",
        "name": "Microsoft.Extensions.DependencyInjection.5uca2vr0u0.wasm",
        "hash": "sha256-g9IWKrzfFdPHol+mXiAkFtrd+e2MIJsJ24KlqW7ToFQ=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.DependencyInjection.Abstractions.wasm",
        "name": "Microsoft.Extensions.DependencyInjection.Abstractions.vjcv19yf40.wasm",
        "hash": "sha256-S5Y56RqqzVlDLX36PTo+z14Bpsk9tQHnt3jcTDh5i48=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.Logging.wasm",
        "name": "Microsoft.Extensions.Logging.5w9ia7xlgq.wasm",
        "hash": "sha256-lRjcnCHTmTMrXGDBaGeTpsqaIN8HKNvcCHj+b6hrj5Q=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.Logging.Abstractions.wasm",
        "name": "Microsoft.Extensions.Logging.Abstractions.hyr6hrp8cs.wasm",
        "hash": "sha256-CWDOH6i7pG8OQBcQTgvYzQBND7Hos3abs0KJifQuAqo=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.Options.wasm",
        "name": "Microsoft.Extensions.Options.espvvlqak1.wasm",
        "hash": "sha256-TqcuRfsHDo1I9fA6NleVgI/6BwkPHpdQbhJJ7PXpJ7Q=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.Extensions.Primitives.wasm",
        "name": "Microsoft.Extensions.Primitives.crxdkm6t9l.wasm",
        "hash": "sha256-KDipvixud3wtosnTZ5tXsIUx4dF6DiSZ4kK8quRG+xo=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.JSInterop.wasm",
        "name": "Microsoft.JSInterop.0s2ioyhd7k.wasm",
        "hash": "sha256-kA4OIaWbB6obNbKQ/H9VxCYrWP9jiy3WDB3jS21K2o8=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "Microsoft.JSInterop.WebAssembly.wasm",
        "name": "Microsoft.JSInterop.WebAssembly.5ql0ra5mpq.wasm",
        "hash": "sha256-A1+r9KkQJ1Kkm98VWekGd8z84GzOPdY9wh/SHfyyQg4=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Collections.Concurrent.wasm",
        "name": "System.Collections.Concurrent.n7j5iw2i4j.wasm",
        "hash": "sha256-g2L0OPyi/TdxVT0wfz3RA43AVr97qmRDnsX0G5n+3Oc=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Collections.Immutable.wasm",
        "name": "System.Collections.Immutable.cunnsehbnj.wasm",
        "hash": "sha256-jMOcvkApUOP80v8WixTvYK6mVgGTYFzDR7YsBs3PlCo=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Collections.wasm",
        "name": "System.Collections.oj7w44o0qr.wasm",
        "hash": "sha256-zH0In107oX/9DwsEX0so9dJWX02p3+nIuv0T/eZ9WHQ=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.ComponentModel.wasm",
        "name": "System.ComponentModel.g3pwvcjcrg.wasm",
        "hash": "sha256-9Fm6GAyFmib1JLplgnBmpwua/+VI5d5CwLicsduEa1U=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Console.wasm",
        "name": "System.Console.w95crtqw1p.wasm",
        "hash": "sha256-tNSVmQ4EWWNrsR0XJCp47rGdTGAfy2iHodrjNgeDMdI=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Diagnostics.DiagnosticSource.wasm",
        "name": "System.Diagnostics.DiagnosticSource.2h9ux9nxld.wasm",
        "hash": "sha256-dsAcz2wzVucNlYwuaeGC+x/HaWF9CXjSEm4X/cICX3E=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.IO.Pipelines.wasm",
        "name": "System.IO.Pipelines.d9jdqrkwk1.wasm",
        "hash": "sha256-P3efMSOfVCOb4KRJxC8w5mdhwshNltAbNhD4m3E4ekU=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Linq.wasm",
        "name": "System.Linq.t4ngorle6i.wasm",
        "hash": "sha256-4/IbhQk9aiuHUPkVLgOarvh4C8n1rHZLb7SLN1hJXk4=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Memory.wasm",
        "name": "System.Memory.qt65tksv1c.wasm",
        "hash": "sha256-zj7wFI7BuKsFcX4XlQeZ53Ora2C3IcoigWyGBEEisF8=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Net.Http.Json.wasm",
        "name": "System.Net.Http.Json.b8a3nkui3h.wasm",
        "hash": "sha256-dQLAzillxnab2rC7XvgSRpEvnGZuTARyRcvk/9DcOpM=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Net.Http.wasm",
        "name": "System.Net.Http.x54xse8sx0.wasm",
        "hash": "sha256-q1j9FxTjV1b5TPQ741u0uilnZY2f5yZp+XV1yLe8Kco=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Net.Primitives.wasm",
        "name": "System.Net.Primitives.be7t0gdwz9.wasm",
        "hash": "sha256-s9rRUomPsrQ7CPYjy7VfHuyk+5Zcz4sL/Uy1Bx9uYZg=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Private.Uri.wasm",
        "name": "System.Private.Uri.0izfylfuug.wasm",
        "hash": "sha256-iFjyLHBSazigN+29kGWA5xuPwT9upcuLOjZ+jCK8M0Q=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Runtime.wasm",
        "name": "System.Runtime.u55pinn5zm.wasm",
        "hash": "sha256-nltvLDgLireFZu5MAlDLoo4WtFDxj2oPHTbbKWKpYeg=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Security.Cryptography.wasm",
        "name": "System.Security.Cryptography.knctfgxfsk.wasm",
        "hash": "sha256-sJvdG9iEW9S28wAoeZPsjuZ+Dn5lEaiWXp+3JNDZgcw=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Text.Encodings.Web.wasm",
        "name": "System.Text.Encodings.Web.7rud52crix.wasm",
        "hash": "sha256-88n6YaAYpvpNnGTEcoOlq+SJlj/VBoWGJl7CJQm8tDI=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Text.Json.wasm",
        "name": "System.Text.Json.091mk3bhgh.wasm",
        "hash": "sha256-LODT407HSdv6WCComPTsLgwEtcN2lXT0sCpJJSDjEFI=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "System.Text.RegularExpressions.wasm",
        "name": "System.Text.RegularExpressions.0rrilxv7qg.wasm",
        "hash": "sha256-BJoYlgx4zWr/Ut7R2HdSbR/o+HMgBRhmUlMWFeaiSpE=",
        "cache": "force-cache"
      },
      {
        "virtualPath": "app.wasm",
        "name": "app.ol0snvssxq.wasm",
        "hash": "sha256-9PqWaFhp2I3RjF11v1NVqmfzmDjA80Eo1f5ELPMhsEY=",
        "cache": "force-cache"
      }
    ]
  },
  "debugLevel": 0,
  "linkerEnabled": true,
  "globalizationMode": "sharded",
  "extensions": {
    "blazor": {}
  },
  "runtimeConfig": {
    "runtimeOptions": {
      "configProperties": {
        "Microsoft.AspNetCore.Components.Routing.RegexConstraintSupport": false,
        "Microsoft.Extensions.DependencyInjection.VerifyOpenGenericServiceTrimmability": true,
        "System.ComponentModel.DefaultValueAttribute.IsSupported": false,
        "System.ComponentModel.Design.IDesignerHost.IsSupported": false,
        "System.ComponentModel.TypeConverter.EnableUnsafeBinaryFormatterInDesigntimeLicenseContextSerialization": false,
        "System.ComponentModel.TypeDescriptor.IsComObjectDescriptorSupported": false,
        "System.Data.DataSet.XmlSerializationIsSupported": false,
        "System.Diagnostics.Debugger.IsSupported": false,
        "System.Diagnostics.Metrics.Meter.IsSupported": false,
        "System.Diagnostics.Tracing.EventSource.IsSupported": false,
        "System.GC.Server": true,
        "System.Globalization.Invariant": false,
        "System.TimeZoneInfo.Invariant": false,
        "System.Linq.Enumerable.IsSizeOptimized": true,
        "System.Net.Http.EnableActivityPropagation": false,
        "System.Net.Http.WasmEnableStreamingResponse": true,
        "System.Net.SocketsHttpHandler.Http3Support": false,
        "System.Reflection.Metadata.MetadataUpdater.IsSupported": false,
        "System.Resources.ResourceManager.AllowCustomResourceTypes": false,
        "System.Resources.UseSystemResourceKeys": true,
        "System.Runtime.CompilerServices.RuntimeFeature.IsDynamicCodeSupported": true,
        "System.Runtime.InteropServices.BuiltInComInterop.IsSupported": false,
        "System.Runtime.InteropServices.EnableConsumingManagedCodeFromNativeHosting": false,
        "System.Runtime.InteropServices.EnableCppCLIHostActivation": false,
        "System.Runtime.InteropServices.Marshalling.EnableGeneratedComInterfaceComImportInterop": false,
        "System.Runtime.Serialization.EnableUnsafeBinaryFormatterSerialization": false,
        "System.StartupHookProvider.IsSupported": false,
        "System.Text.Encoding.EnableUnsafeUTF7Encoding": false,
        "System.Text.Json.JsonSerializer.IsReflectionEnabledByDefault": true,
        "System.Threading.Thread.EnableAutoreleasePool": false,
        "Microsoft.AspNetCore.Components.Endpoints.NavigationManager.DisableThrowNavigationException": false
      }
    }
  }
}/*json-end*/;
