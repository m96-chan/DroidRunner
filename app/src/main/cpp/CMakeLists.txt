cmake_minimum_required(VERSION 3.22)
project(droidrunner_native C)

add_library(nnapi_probe SHARED nnapi_probe.c)
target_link_libraries(nnapi_probe)

# Loaded only in the ":qnn" process. Kept a separate library so nothing that
# touches Qualcomm's runtime can be pulled into the process that runs PRoot.
add_library(qnn_probe SHARED qnn_probe.c)
target_link_libraries(qnn_probe dl)
