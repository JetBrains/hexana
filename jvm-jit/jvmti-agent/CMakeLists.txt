cmake_minimum_required(VERSION 3.2)
project(jitview C)

string(TOLOWER ${CMAKE_SYSTEM_NAME} SYSTEM_PREFIX)

message("JAVA_HOME=$ENV{JAVA_HOME}")
file(TO_CMAKE_PATH $ENV{JAVA_HOME} CMAKE_JAVA_HOME)
message("CMAKE_JAVA_HOME=${CMAKE_JAVA_HOME}")


if(APPLE)
  set(CMAKE_OSX_ARCHITECTURES "x86_64;arm64")
  set(CMAKE_OSX_DEPLOYMENT_TARGET 10.12.2)
endif()

add_library(scheduled SHARED
  src/agent.c
)
set_property(TARGET scheduled
  PROPERTY INCLUDE_DIRECTORIES ${CMAKE_JAVA_HOME}/include)
if (WIN32)
  target_include_directories(scheduled PRIVATE ${CMAKE_JAVA_HOME}/include/win32)
  target_link_libraries(scheduled Ws2_32)
else()
  if (LINUX)
    add_definitions(-D_DEFAULT_SOURCE)
  endif(LINUX)
  target_include_directories(scheduled PRIVATE ${CMAKE_JAVA_HOME}/include/$<LOWER_CASE:$<PLATFORM_ID>>)
endif(WIN32)

set(CMAKE_VERBOSE_MAKEFILE ON)