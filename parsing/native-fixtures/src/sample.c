#if defined(_WIN32)
#define HEXANA_EXPORT __declspec(dllexport)
#else
#define HEXANA_EXPORT __attribute__((visibility("default")))
#endif

volatile int hexana_counter = 7;
const char hexana_message[] = "hexana native parser fixture";

HEXANA_EXPORT int hexana_add(int left, int right) {
    return left + right + hexana_counter;
}

static int hexana_square(int value) {
    return value * value;
}

int (*hexana_add_ptr)(int, int) = hexana_add;

HEXANA_EXPORT int hexana_dispatch(int selector) {
    switch (selector) {
        case 0:
            return hexana_add(1, 2);
        case 1:
            return hexana_square(5);
        default:
            return hexana_message[0] + selector;
    }
}

#if defined(_WIN32)
int mainCRTStartup(void) {
    return hexana_dispatch(1);
}
#else
int main(int argc, char **argv) {
    return hexana_dispatch(argc) + (argv != 0);
}
#endif
