using System;
using System.Collections.Generic;

namespace Hexana.R2rSample
{
    public static class Calculator
    {
        public static int Add(int a, int b) => a + b;

        public static int Fib(int n) => n < 2 ? n : Fib(n - 1) + Fib(n - 2);

        public static int Parse(string text)
        {
            try
            {
                return int.Parse(text);
            }
            catch (FormatException)
            {
                return -1;
            }
        }

        public static T Max<T>(T a, T b) where T : IComparable<T> => a.CompareTo(b) >= 0 ? a : b;

        public static int MaxOfInts(int a, int b) => Max(a, b);

        public static List<Point> Points(int count)
        {
            var points = new List<Point>();
            for (var i = 0; i < count; i++) points.Add(new Point(i, i * 2));
            return points;
        }
    }

    public readonly struct Point
    {
        public Point(int x, int y) { X = x; Y = y; }
        public int X { get; }
        public int Y { get; }
        public override string ToString() => $"({X}, {Y})";

        public sealed class Formatter
        {
            public string Format(Point point) => point.ToString();
        }
    }

    public sealed class Box<T>
    {
        public T Value;
        public Box(T value) { Value = value; }
        public override string ToString() => Value?.ToString() ?? "null";
    }

    public static class Boxes
    {
        public static string Describe() => new Box<int>(42).ToString() + new Box<Point>(new Point(1, 2)).ToString();
    }
}
