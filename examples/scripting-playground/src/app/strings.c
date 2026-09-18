/* strings.c - static string data for DWARF and data-segment demos */

#include "strings.h"

/* A recognizable greeting stored in the data segment */
static const char greeting[] = "Hello from Hexana scripting playground";

/* A secondary banner string */
static const char banner[] = "app-debug: wasm32-wasi reactor built with wasi-sdk 27.0";

const char *strings_greeting(void) { return greeting; }
const char *strings_banner(void)   { return banner; }
