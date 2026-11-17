static int add(int a, int b) {
    return a + b;
}

void _start(void) {
    volatile int result = add(1, 2);
    (void)result;
}
