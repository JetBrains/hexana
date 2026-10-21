// Dumps what Hexana's portable PDB tests check, through the runtime's own System.Reflection.Metadata reader.
using System;
using System.IO;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;

static class DumpPortablePdb
{
    static void Main(string[] args)
    {
        using var stream = File.OpenRead(args[0]);
        using var provider = MetadataReaderProvider.FromPortablePdbStream(stream);
        var reader = provider.GetMetadataReader();
        var id = new BlobContentId(reader.DebugMetadataHeader.Id);
        Console.WriteLine($"metadata version: {reader.MetadataVersion}");
        Console.WriteLine($"pdb id: guid={id.Guid:D} stamp=0x{id.Stamp:X8}");
        var entryPoint = reader.DebugMetadataHeader.EntryPoint;
        Console.WriteLine($"entry point: 0x{(entryPoint.IsNil ? 0 : MetadataTokens.GetToken(entryPoint)):X8}");
        for (var table = 0; table < MetadataTokens.TableCount; table++)
        {
            var rows = reader.GetTableRowCount((TableIndex)table);
            if (rows > 0) Console.WriteLine($"table {(TableIndex)table}: {rows}");
        }
        foreach (var handle in reader.Documents)
        {
            var document = reader.GetDocument(handle);
            var hash = reader.GetBlobBytes(document.Hash);
            Console.WriteLine($"document {MetadataTokens.GetRowNumber(handle)}: {reader.GetString(document.Name)} " +
                $"hash={reader.GetGuid(document.HashAlgorithm):D}:{Convert.ToHexString(hash)} language={reader.GetGuid(document.Language):D}");
        }
        foreach (var handle in reader.MethodDebugInformation)
        {
            var info = reader.GetMethodDebugInformation(handle);
            var points = info.SequencePointsBlob.IsNil ? 0 : info.GetSequencePoints().Count();
            Console.WriteLine($"method 0x{MetadataTokens.GetToken(handle.ToDefinitionHandle()):X8}: document={(info.Document.IsNil ? 0 : MetadataTokens.GetRowNumber(info.Document))} sequence points={points}");
        }
    }
}
