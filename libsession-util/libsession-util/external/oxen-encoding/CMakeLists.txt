cmake_minimum_required(VERSION 3.10)  # bionic's cmake version

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Has to be set before `project()`, and ignored on non-macos:
set(CMAKE_OSX_DEPLOYMENT_TARGET 10.12 CACHE STRING "macOS deployment target (Apple clang only)")

find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
  foreach(lang ${LANGS})
    if(NOT DEFINED CMAKE_${lang}_COMPILER_LAUNCHER AND NOT CMAKE_${lang}_COMPILER MATCHES ".*/ccache")
      message(STATUS "Enabling ccache for ${lang}")
      set(CMAKE_${lang}_COMPILER_LAUNCHER ${CCACHE_PROGRAM} CACHE STRING "")
    endif()
  endforeach()
endif()

project(oxenc
    VERSION 1.0.10
    DESCRIPTION "oxenc - Base 16/32/64 and Bittorrent Encoding/Decoding Header Only Library"
    LANGUAGES CXX)


set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(GNUInstallDirs)

message(STATUS "oxenc v${PROJECT_VERSION}")

if(CMAKE_PROJECT_NAME STREQUAL PROJECT_NAME)
    set(oxenc_IS_TOPLEVEL_PROJECT TRUE)
else()
    set(oxenc_IS_TOPLEVEL_PROJECT FALSE)
endif()

option(OXENC_BUILD_TESTS "Building and perform oxenc tests" ${oxenc_IS_TOPLEVEL_PROJECT})
option(OXENC_BUILD_DOCS "Build oxenc documentation" ${oxenc_IS_TOPLEVEL_PROJECT})
option(OXENC_INSTALL "Add oxenc headers to install target" ${oxenc_IS_TOPLEVEL_PROJECT})


configure_file(oxenc/version.h.in oxenc/version.h @ONLY)
configure_file(liboxenc.pc.in liboxenc.pc @ONLY)


add_library(oxenc INTERFACE)

target_include_directories(oxenc
    INTERFACE
        $<INSTALL_INTERFACE:>
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>)

add_library(oxenc::oxenc ALIAS oxenc)

export(
    TARGETS oxenc
    NAMESPACE oxenc::
    FILE oxencTargets.cmake
)

if(OXENC_INSTALL)
  install(
    TARGETS oxenc
    EXPORT oxencConfig
    DESTINATION ${CMAKE_INSTALL_LIBDIR}
    )

  install(
    FILES
    oxenc/base32z.h
    oxenc/base64.h
    oxenc/bt.h
    oxenc/bt_producer.h
    oxenc/bt_serialize.h
    oxenc/bt_value.h
    oxenc/bt_value_producer.h
    oxenc/byte_type.h
    oxenc/endian.h
    oxenc/hex.h
    oxenc/variant.h
    ${CMAKE_CURRENT_BINARY_DIR}/oxenc/version.h
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/oxenc
    )


  install(
    FILES ${CMAKE_CURRENT_BINARY_DIR}/liboxenc.pc
    DESTINATION ${CMAKE_INSTALL_DATADIR}/pkgconfig
    )
endif()


if(OXENC_BUILD_DOCS)
  add_subdirectory(docs)
endif()

if(OXENC_BUILD_TESTS)
  add_subdirectory(tests)
endif()
