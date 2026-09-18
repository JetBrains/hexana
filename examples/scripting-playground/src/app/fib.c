/* fib.c - iterative Fibonacci implementation */

#include "fib.h"

/* Returns the nth Fibonacci number (0-indexed, iterative, avoids stack growth).
 * fib(0)=0, fib(1)=1, fib(2)=1, fib(3)=2, fib(10)=55, fib(20)=6765 */
int fib(int n) {
    if (n <= 0) return 0;
    if (n == 1) return 1;
    int a = 0, b = 1;
    for (int i = 2; i <= n; i++) {
        int tmp = a + b;
        a = b;
        b = tmp;
    }
    return b;
}
