# Minimum CMake version required
cmake_minimum_required(VERSION 3.22.1)

# Project name
project("streamengine")

# Add the C++ source file as a shared library
add_library(
        streamengine
        SHARED
        stream_engine.cpp
)

# Find Android logging library
find_library(
        log-lib
        log
)

# Enable NEON for 32-bit ARM
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -mfloat-abi=softfp")
endif()

# Link libraries
target_link_libraries(
        streamengine
        ${log-lib}
)
