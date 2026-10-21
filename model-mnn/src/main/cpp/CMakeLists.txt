cmake_minimum_required(VERSION 3.22.1)
project(kiora_mnn_bridge LANGUAGES CXX)

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libMNN.so")
add_library(llm SHARED IMPORTED)
set_target_properties(llm PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libllm.so")

add_library(kiora_mnn_bridge SHARED mnn_bridge.cpp)
target_compile_features(kiora_mnn_bridge PRIVATE cxx_std_17)
target_compile_options(kiora_mnn_bridge PRIVATE -fvisibility=hidden)
target_link_options(kiora_mnn_bridge PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(kiora_mnn_bridge PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/include/3rd_party"
    "${CMAKE_CURRENT_SOURCE_DIR}/include/express")
target_link_libraries(kiora_mnn_bridge PRIVATE llm MNN android log)
