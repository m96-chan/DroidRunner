cmake_minimum_required(VERSION 3.22)
project(nnapi_probe C)

add_library(nnapi_probe SHARED nnapi_probe.c)
target_link_libraries(nnapi_probe)
