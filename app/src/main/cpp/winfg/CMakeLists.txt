# Win-FG frame generation (The412Banner/win-fg, MIT): an alternative to the Lossless Scaling core. Same C interface, no DLL needed.
add_library(winfgcore SHARED bridge.cpp framegen.cpp)
set_target_properties(winfgcore PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_VISIBILITY_PRESET hidden)
target_include_directories(winfgcore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(winfgcore PRIVATE VK_NO_PROTOTYPES=1 VK_USE_PLATFORM_ANDROID_KHR)
target_link_libraries(winfgcore PRIVATE log)
