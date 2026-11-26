cmake_minimum_required(VERSION 3.22)
project(droidrunner_native C)

add_library(nnapi_probe SHARED nnapi_probe.c)
target_link_libraries(nnapi_probe)

# Loaded only in the main process, which is the one that touches the guest's
# files. The unit tests build the same source for the host JVM.
add_library(guest_fs SHARED guest_fs.c)

# Loaded only in the ":qnn" process. Kept a separate library so nothing that
# touches Qualcomm's runtime can be pulled into the process that runs PRoot.
add_library(qnn_probe SHARED qnn_probe.c)
target_link_libraries(qnn_probe dl)
