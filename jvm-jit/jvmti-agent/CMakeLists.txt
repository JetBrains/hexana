cmake_minimum_required(VERSION 3.16)
project(jitview C)

string(TOLOWER ${CMAKE_SYSTEM_NAME} SYSTEM_PREFIX)

message("JAVA_HOME=$ENV{JAVA_HOME}")
file(TO_CMAKE_PATH $ENV{JAVA_HOME} CMAKE_JAVA_HOME)
message("CMAKE_JAVA_HOME=${CMAKE_JAVA_HOME}")

if(APPLE)
  set(CMAKE_OSX_ARCHITECTURES "x86_64;arm64")
  set(CMAKE_OSX_DEPLOYMENT_TARGET 10.15)
endif()

add_library(jitview SHARED src/agent.c)

# Required headers ship with the JDK at $JAVA_HOME/include and
# $JAVA_HOME/include/<os>; jvmticmlr.h lives next to jvmti.h in the same
# directory.
target_include_directories(jitview PRIVATE ${CMAKE_JAVA_HOME}/include)

if(WIN32)
  target_include_directories(jitview PRIVATE ${CMAKE_JAVA_HOME}/include/win32)
  # Ws2_32: sockets (pulled in transitively by the JVM on Windows).
  # Psapi: EnumProcessModules / GetModuleInformation used by enumerate_libraries
  #        when dump_libraries is active. Both MSVC and MinGW accept these names.
  target_link_libraries(jitview Ws2_32 Psapi)
else()
  # Match Linux explicitly via CMAKE_SYSTEM_NAME — the shorthand `LINUX`
  # variable was only added in CMake 3.25, and our minimum is 3.16, so on
  # older agents the `if(LINUX)` block would silently never run.
  if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    # _GNU_SOURCE is required for glibc to expose dl_phdr_info and
    # dl_iterate_phdr from <link.h> — they are gated by __USE_GNU, which
    # _DEFAULT_SOURCE alone does not set. Without this the agent.c
    # enumerate_libraries() path fails to compile with
    # "invalid use of undefined type 'struct dl_phdr_info'".
    add_definitions(-D_GNU_SOURCE)
  endif()
  target_include_directories(jitview PRIVATE ${CMAKE_JAVA_HOME}/include/$<LOWER_CASE:$<PLATFORM_ID>>)
endif()

set(CMAKE_VERBOSE_MAKEFILE ON)
