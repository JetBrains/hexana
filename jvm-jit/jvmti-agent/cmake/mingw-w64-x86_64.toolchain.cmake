# CMake toolchain file for cross-compiling the JVMTI agent to
# Windows x86_64 from a Linux host using the gcc-mingw-w64 toolchain.
#
# Activated by the `jvm-jit.crossTarget=windows-x86_64` Gradle property,
# which adds `-DCMAKE_TOOLCHAIN_FILE=<this file>` to the cmake invocation.
#
# Assumes the `gcc-mingw-w64-x86-64` apt package is installed on the host
# (the TeamCity slice's first build step apt-installs it).

set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

set(CMAKE_C_COMPILER   x86_64-w64-mingw32-gcc)
set(CMAKE_CXX_COMPILER x86_64-w64-mingw32-g++)
set(CMAKE_RC_COMPILER  x86_64-w64-mingw32-windres)

# Look for libraries / headers inside the mingw sysroot, not the host's.
# Programs (compilers, codegen helpers) still come from the host PATH.
set(CMAKE_FIND_ROOT_PATH /usr/x86_64-w64-mingw32)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
