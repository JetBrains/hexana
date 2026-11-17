using System;
using System.IO;
using System.Resources;

[Serializable]
public struct Margin { public int Left; public int Top; }

public static class MakeResources
{
    public static void Main(string[] args)
    {
        using (var writer = new ResourceWriter(args[0]))
        {
            writer.AddResource("Greeting", "Hello from Hexana");
            writer.AddResource("Multiline", "line one\nline two");
            writer.AddResource("Empty", "");
            writer.AddResource("Answer", 42);
            writer.AddResource("Negative", (short)-7);
            writer.AddResource("Big", 1234567890123456789L);
            writer.AddResource("Unsigned", 4000000000u);
            writer.AddResource("Flag", true);
            writer.AddResource("Letter", 'Z');
            writer.AddResource("Ratio", 0.25);
            writer.AddResource("Single", 1.5f);
            writer.AddResource("Price", 12345.6789m);
            writer.AddResource("Byte", (byte)255);
            writer.AddResource("Released", new DateTime(2026, 10, 20, 13, 45, 30, DateTimeKind.Utc));
            writer.AddResource("Timeout", new TimeSpan(1, 2, 3, 4, 500));
            writer.AddResource("Nothing", (object)null);
            writer.AddResource("Pixel", new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D });
            writer.AddResource("Blob", new MemoryStream(new byte[] { 1, 2, 3, 4, 5 }));
            writer.AddResource("Margin", new Margin { Left = 4, Top = 8 });
        }
    }
}
