// Regression fixture for the assembly diff (ClrDiffProviderTest), built twice: without HEAD as
// sample-clr-review-base.dll and with -define:HEAD as sample-clr-review-head.dll.
//  - Uses.Take / Uses.Make: the same short type name, `Widget`, from another namespace.
//  - Strings.Label: a user string that reads like the generated RVA comment.
//  - Twice moves from Source to Target unchanged; both types keep another method.
namespace A { public class Widget { } }
namespace B { public class Widget { } }

namespace Hexana.Review
{
    public static class Uses
    {
#if HEAD
        public static void Take(B.Widget w) { }
        public static object Make() { return new B.Widget(); }
#else
        public static void Take(A.Widget w) { }
        public static object Make() { return new A.Widget(); }
#endif
    }

    public static class Strings
    {
#if HEAD
        public static string Label() { return "RVA 0x5678, code size 1"; }
#else
        public static string Label() { return "RVA 0x1234, code size 1"; }
#endif
    }

    public static class Source
    {
        public static int Stay(int x) { return x + 1; }
#if !HEAD
        public static int Twice(int x) { return x * 2 + 7; }
#endif
    }

    public static class Target
    {
        public static int Keep(int x) { return x - 1; }
#if HEAD
        public static int Twice(int x) { return x * 2 + 7; }
#endif
    }
}
