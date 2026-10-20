namespace Hexana.R2rSample
{
    public static class Calculator
    {
        public static int Add(int a, int b) => a + b;
        public static int Fib(int n) => n < 2 ? n : Fib(n - 1) + Fib(n - 2);
    }
}
