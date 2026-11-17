// Dumps what Hexana's portable PDB tests check, through the runtime's own System.Reflection.Metadata reader.
// The argument is a standalone .pdb, or an assembly whose debug directory embeds its Portable PDB.
using System;
using System.IO;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;

static class DumpPortablePdb
{
    static void Main(string[] args)
    {
        using var stream = File.OpenRead(args[0]);
        using var peReader = args[0].EndsWith(".pdb") ? null : new PEReader(stream);
        using var provider = peReader == null
            ? MetadataReaderProvider.FromPortablePdbStream(stream)
            : peReader.ReadEmbeddedPortablePdbDebugDirectoryData(
                peReader.ReadDebugDirectory().Single(entry => entry.Type == DebugDirectoryEntryType.EmbeddedPortablePdb));
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
            if (info.SequencePointsBlob.IsNil) continue;
            foreach (var point in info.GetSequencePoints())
            {
                var document = MetadataTokens.GetRowNumber(point.Document);
                Console.WriteLine(point.IsHidden
                    ? $"  IL_{point.Offset:X4} document={document} hidden"
                    : $"  IL_{point.Offset:X4} document={document} ({point.StartLine},{point.StartColumn})-({point.EndLine},{point.EndColumn})");
            }
        }
    }
}
