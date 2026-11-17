int arm_add(int a, int b) { return a + b * 2; }

__attribute__((target("thumb")))
int thumb_mul(int a, int b) { return a * b + 7; }

void _start(void) { arm_add(1, thumb_mul(2, 3)); }
