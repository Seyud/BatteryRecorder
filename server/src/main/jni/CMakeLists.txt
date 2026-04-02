cmake_minimum_required(VERSION 3.22.1)
project("batteryrecorder")

add_library(batteryrecorder SHARED
        jni_main.cpp
        power_reader.cpp
        dump_parser.cpp
)

find_library(log-lib log)

target_link_libraries(batteryrecorder
        ${log-lib}
)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra")
