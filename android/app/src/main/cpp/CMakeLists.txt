cmake_minimum_required(VERSION 3.18)
project(mesh_bridge C)

# Minimal FFI bridge for MeshNet crypto operations.
# This is a foundation for future Dart FFI migration —
# the actual crypto stays in Kotlin/JVM for now.

add_library(mesh_bridge SHARED
    mesh_bridge.c
)

target_link_libraries(mesh_bridge
    log
)

# Ensure the library is built for all ABIs
set_target_properties(mesh_bridge PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)
