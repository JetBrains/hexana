# firmware test data

DTS files are copied from `ui-driver-tests/testData/fixtures/zephyr-corpus/apps/hello_world/<board>-Os/zephyr.dts`.
Config files are synthetic Zephyr `.config` stubs containing only `CONFIG_BOARD` and `CONFIG_BOARD_QUALIFIERS`.
These files drive the golden command-line and `.resc` tests in `FirmwareLaunchGoldenTest`.
