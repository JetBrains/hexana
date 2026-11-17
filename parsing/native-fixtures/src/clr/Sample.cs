using System;
using System.Collections.Generic;

[assembly: System.Reflection.AssemblyVersion("1.2.3.4")]
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8")]

namespace Hexana.Sample
{
    public interface IGreeter
    {
        string Greet(string name);
    }

    public class Greeter : IGreeter
    {
        private readonly string _prefix;
        public static int Counter;

        public Greeter(string prefix) { _prefix = prefix; }

        public string Greet(string name)
        {
            Counter++;
            return _prefix + ", " + name + "!";
        }

        public string Prefix { get { return _prefix; } }

        public event EventHandler Greeted;

        public class Nested { public int Value; }
    }

    public class Box<T>
    {
        public T Value;

        public U Map<U>(Func<T, U> map) { return map(Value); }
    }

    public enum Color { Red, Green, Blue }

    public static class Program
    {
        public static int Main(string[] args)
        {
            var list = new List<string> { "a", "b" };
            var g = new Greeter("Hello");
            Console.WriteLine(g.Greet(list[0]));
            return list.Count;
        }

        public static string Describe(int value)
        {
            try
            {
                switch (value)
                {
                    case 0: return "zero";
                    case 1: return "one";
                    case 2: return "two";
                    default: return new Box<int> { Value = value }.Map<string>(v => v.ToString());
                }
            }
            catch (FormatException)
            {
                return "bad";
            }
            finally
            {
                Greeter.Counter++;
            }
        }
    }
}
