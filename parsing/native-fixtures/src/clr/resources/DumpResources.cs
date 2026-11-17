using System;
using System.Collections;
using System.Resources;

public static class DumpResources
{
    public static void Main(string[] args)
    {
        using (var reader = new ResourceReader(args[0]))
        {
            foreach (DictionaryEntry entry in reader)
            {
                string type; byte[] data;
                reader.GetResourceData((string)entry.Key, out type, out data);
                var value = entry.Value is byte[] b ? "byte[" + b.Length + "]" : entry.Value is System.IO.Stream s ? "stream[" + s.Length + "]" :
                    entry.Value is DateTime d ? d.ToString("o") : entry.Value is TimeSpan t ? t.ToString() : Convert.ToString(entry.Value, System.Globalization.CultureInfo.InvariantCulture);
                Console.WriteLine(entry.Key + " | " + type + " | " + data.Length + " | " + (value ?? "<null>").Replace("\n", "\\n"));
            }
        }
    }
}
