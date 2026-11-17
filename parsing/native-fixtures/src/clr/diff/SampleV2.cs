using System;
using System.Collections.Generic;

// Head side of the .NET diff fixture: Sample.cs (the base) with
//  - Greeter.Greet's body changed, Greeter.Prefix removed,
//  - Program.Describe gaining a case, Program.Main left as is (its tokens shift),
//  - Color gaining a member, and a new type Farewell.
[assembly: System.Reflection.AssemblyVersion("1.2.4.0")]
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.8")]

namespace Hexana.Sample
{
    public interface IGreeter
    {
        string Greet(string name);
    }

    public class Farewell
    {
        public static string Bye(string name) { return "Bye, " + name; }
    }

    public class Greeter : IGreeter
    {
        private readonly string _prefix;
        public static int Counter;

        public Greeter(string prefix) { _prefix = prefix; }

        public string Greet(string name)
        {
            Counter += 2;
            return _prefix + ", dear " + name + "!";
        }

        public event EventHandler Greeted;

        public class Nested { public int Value; }
    }

    public class Box<T>
    {
        public T Value;

        public U Map<U>(Func<T, U> map) { return map(Value); }
    }

    public enum Color { Red, Green, Blue, Alpha }

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
                    case 3: return "three";
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
