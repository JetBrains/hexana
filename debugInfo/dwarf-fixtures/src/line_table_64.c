static int twice(int value) {
    return value * 2;
}

int main(void) {
    volatile int value = twice(21);
    return value == 42 ? 0 : 1;
}
