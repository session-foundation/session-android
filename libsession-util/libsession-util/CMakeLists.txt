cmake_minimum_required(VERSION 3.14...3.23)

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Has to be set before `project()`, and ignored on non-macos:
set(CMAKE_OSX_DEPLOYMENT_TARGET 10.13 CACHE STRING "macOS deployment target (Apple clang only)")

set(LANGS C CXX)
find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
  foreach(lang ${LANGS})
    if(NOT DEFINED CMAKE_${lang}_COMPILER_LAUNCHER AND NOT CMAKE_${lang}_COMPILER MATCHES ".*/ccache")
      message(STATUS "Enabling ccache for ${lang}")
      set(CMAKE_${lang}_COMPILER_LAUNCHER ${CCACHE_PROGRAM} CACHE STRING "")
    endif()
  endforeach()
endif()


project(libsession-util
    VERSION 1.2.0
    DESCRIPTION "Session client utility library"
    LANGUAGES ${LANGS})

message(STATUS "${PROJECT_NAME} v${PROJECT_VERSION}")

set(LIBSESSION_LIBVERSION ${PROJECT_VERSION})

include(GNUInstallDirs)

list(APPEND CMAKE_MODULE_PATH "${PROJECT_SOURCE_DIR}/cmake")

# No in-source building
include(MacroEnsureOutOfSourceBuild)
macro_ensure_out_of_source_build("${PROJECT_NAME} requires an out-of-source build.  Create a build directory and run 'cmake ${PROJECT_SOURCE_DIR} [options]'.")

if(CMAKE_PROJECT_NAME STREQUAL PROJECT_NAME)
    set(libsession_IS_TOPLEVEL_PROJECT TRUE)
else()
    set(libsession_IS_TOPLEVEL_PROJECT FALSE)
endif()


set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(default_static_libstd OFF)
if(WIN32)
    set(default_static_libstd ON)
endif()

option(BUILD_SHARED_LIBS "Build as shared library" OFF)

if(BUILD_SHARED_LIBS)
    set(static_default OFF)
else()
    set(static_default ON)
endif()

option(BUILD_STATIC_DEPS "Build all dependencies statically rather than trying to link to them on the system" ${static_default})
option(STATIC_BUNDLE "Build a single static .a containing everything (both code and dependencies)" ${static_default})

if(BUILD_SHARED_LIBS OR libsession_IS_TOPLEVEL_PROJECT)
    set(install_default ON)
else()
    set(install_default OFF)
endif()

option(LIBSESSION_INSTALL "Install libsession-util libraries and headers to cmake install target; defaults to ON if BUILD_SHARED_LIBS is enabled or when building the top-level project" ${install_default})

if(MINGW OR ANDROID OR IOS) # OR STATIC_BUNDLE)
  set(use_lto_default OFF)
else()
  set(use_lto_default ON)
endif()

option(WARNINGS_AS_ERRORS "Treat all compiler warnings as errors" OFF)

option(STATIC_LIBSTD "Statically link libstdc++/libgcc" ${default_static_libstd})

option(USE_LTO "Use Link-Time Optimization" ${use_lto_default})

# Provide this as an option for now because GMP and iOS are sometimes unhappy with each other.
option(ENABLE_ONIONREQ "Build with onion request functionality" ON)

if(USE_LTO)
  include(CheckIPOSupported)
  check_ipo_supported(RESULT IPO_ENABLED OUTPUT ipo_error)
  if(IPO_ENABLED)
    message(STATUS "LTO enabled")
  else()
    message(WARNING "LTO not supported by compiler: ${ipo_error}")
  endif()
else()
  message(STATUS "LTO disabled")
  set(IPO_ENABLED OFF)
endif()

if(IPO_ENABLED AND NOT DEFINED CMAKE_INTERPROCEDURAL_OPTIMIZATION)
  set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
endif()

if(STATIC_LIBSTD)
    add_link_options(-static-libstdc++)
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES Clang)
        add_link_options(-static-libgcc)
    endif()
    if(MINGW)
        add_link_options(-static -lwinpthread)
    endif()
endif()

include(AddStaticBundleLib)

# Always build PIC
set(CMAKE_POSITION_INDEPENDENT_CODE ON)


add_subdirectory(external)
add_subdirectory(src)
add_subdirectory(proto)

if (BUILD_STATIC_DEPS)
    include(StaticBuild)
endif()

if(STATIC_BUNDLE)

    include(combine_archives)

    combine_archives(session-util libsession-static-bundle "${LIBSESSION_STATIC_BUNDLE_LIBS}")
    set(lib_folder lib)
    if(IOS)
        set(lib_folder "${lib_folder}-${ARCH}")
    endif()

    install(FILES ${CMAKE_CURRENT_BINARY_DIR}/libsession-util.a
        ARCHIVE DESTINATION ${lib_folder})
endif()


option(WITH_TESTS "Enable unit tests" ${libsession_IS_TOPLEVEL_PROJECT})
if(WITH_TESTS)
    add_subdirectory(tests)
endif()


if(LIBSESSION_INSTALL)

    install(
        TARGETS ${libsession_export_targets}
        EXPORT libsessionConfig
        DESTINATION ${CMAKE_INSTALL_LIBDIR}
    )

    install(DIRECTORY include/session DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}
        PATTERN *.h PATTERN *.hpp)

    set(libsession_target_links)
    foreach(tgt ${libsession_export_targets})
        set(libsession_target_links "${libsession_target_links} -lsession-${tgt}")
    endforeach()
    configure_file(libsession-util.pc.in libsession-util.pc @ONLY)
    install(FILES ${CMAKE_CURRENT_BINARY_DIR}/libsession-util.pc
        DESTINATION ${CMAKE_INSTALL_LIBDIR}/pkgconfig
    )

endif()
