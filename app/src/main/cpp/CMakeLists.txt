cmake_minimum_required(VERSION 3.22.1)
project("llamachat")

set(CMAKE_CXX_STANDARD 17)

# ARM-specific CPU optimizations for Android
if (ANDROID)
    add_compile_options(-O3 -march=armv8-a+dotprod+simd)
endif()

# Disable what we don't need in llama.cpp
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Disable what we don't need in llama.cpp
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Force Vulkan OFF to avoid host compiler errors on Windows
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

# Add llama.cpp as a subdirectory
add_subdirectory(llamacpp)

# Build your JNI bridge as a shared library
add_library(llama_bridge SHARED
    llama_bridge.cpp
)

# Link against the 'llama' target from the subdirectory
target_include_directories(llama_bridge PRIVATE
    llamacpp/include
    llamacpp/ggml/include
)
target_link_libraries(llama_bridge llama android log)