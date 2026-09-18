/* main.c - entry point for the scripting-playground demo reactor
 *
 * Built with wasi-sdk 27.0 (clang 20.1.8, wasm32-wasi) as a reactor
 * (-mexec-model=reactor) so the module exports named functions rather than
 * running _start on instantiation. No heap allocation; all data is static.
 */

#include "strings.h"
#include "fib.h"
#include <string.h>

/* Exported: returns the length of the static greeting */
__attribute__((export_name("greeting_len")))
int greeting_len(void) {
    return (int)strlen(strings_greeting());
}

/* Exported: returns the nth Fibonacci number (iterative) */
__attribute__((export_name("compute_fib")))
int compute_fib(int n) {
    return fib(n);
}

/* Exported: returns the byte at position pos inside the greeting, or -1 */
__attribute__((export_name("greeting_byte")))
int greeting_byte(int pos) {
    const char *g = strings_greeting();
    int len = (int)strlen(g);
    if (pos < 0 || pos >= len) return -1;
    return (unsigned char)g[pos];
}
