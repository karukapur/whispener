# SPIRV-Headers is already added through FetchContent before whisper.cpp.
# This config lets ggml's required find_package() reuse that pinned target.
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    message(FATAL_ERROR "Pinned SPIRV-Headers target was not initialized")
endif()
set(SPIRV-Headers_FOUND TRUE)
