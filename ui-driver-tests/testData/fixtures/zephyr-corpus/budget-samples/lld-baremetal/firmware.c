/* Minimal bare-metal ARM Cortex-M firmware for Hexana Flash/RAM budget testing */

/* Vector table (interrupt vectors as const array => .rodata/.text area) */
extern void _start(void);
__attribute__((section(".vectors")))
const unsigned int vectors[16] = {
    0x20010000,  /* Initial SP */
    (unsigned int)_start,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
};

/* Initialized global data => .data (LMA in FLASH, VMA in RAM) */
volatile int initialized_data[64] = {
    1, 2, 3, 4, 5, 6, 7, 8,
    9, 10, 11, 12, 13, 14, 15, 16,
    17, 18, 19, 20, 21, 22, 23, 24,
    25, 26, 27, 28, 29, 30, 31, 32,
    33, 34, 35, 36, 37, 38, 39, 40,
    41, 42, 43, 44, 45, 46, 47, 48,
    49, 50, 51, 52, 53, 54, 55, 56,
    57, 58, 59, 60, 61, 62, 63, 64
};

/* Zero-initialized large array => .bss */
volatile unsigned char zero_buf[8192];

/* Text function */
__attribute__((noinline))
static int compute(int x, int y) {
    return x * y + initialized_data[x % 64];
}

void _start(void) {
    volatile int r = compute(3, 7);
    (void)r;
    while (1) {}
}
