// Usage: WebcilConvert <input.dll|exe> <output> [--bare]
// Writes the Wasm-wrapped Webcil module Blazor WebAssembly ships, or with --bare the raw `WbIL` payload.
using System;
using Microsoft.NET.WebAssembly.Webcil;

static class WebcilConvert
{
    static int Main(string[] args)
    {
        if (args.Length < 2)
        {
            Console.Error.WriteLine("usage: WebcilConvert <input> <output> [--bare]");
            return 2;
        }
        var converter = WebcilConverter.FromPortableExecutable(args[0], args[1]);
        converter.WrapInWebAssembly = !(args.Length > 2 && args[2] == "--bare");
        converter.ConvertToWebcil();
        return 0;
    }
}
