namespace hexana_fixture {

class Accumulator {
public:
    explicit Accumulator(int seed) : value(seed) {}

    int add(int delta) {
        value += delta;
        return value;
    }

private:
    int value;
};

int compute(int input) {
    Accumulator accumulator(input);
    return accumulator.add(39);
}

}

extern "C" int hexana_cpp_entry(int input) {
    return hexana_fixture::compute(input);
}
