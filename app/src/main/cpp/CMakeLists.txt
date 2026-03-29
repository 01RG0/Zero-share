cmake_minimum_required(VERSION 3.22.1)
project("ultrashare_native")

# RTP Packetizer (low-level, performance-critical)
add_library(
    ultrashare_rtp
    SHARED
    rtp_packetizer.cpp
)

# Link Android system libraries
target_link_libraries(ultrashare_rtp
    android
    log
)

# Maximum optimization for RTP hot path
target_compile_options(ultrashare_rtp PRIVATE
    -O3
    -ffast-math
    -march=armv8-a
)
