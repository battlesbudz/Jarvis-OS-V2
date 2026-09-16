# Loaded only for the host acceptance test; linked to the actual patched native core.
add_executable(jarvis-pocket-stream-check "${CMAKE_CURRENT_LIST_DIR}/../../scripts/check_pocket_stream.cpp")
target_include_directories(jarvis-pocket-stream-check PRIVATE "${PROJECT_SOURCE_DIR}")
target_link_libraries(jarvis-pocket-stream-check sherpa-onnx-c-api)
