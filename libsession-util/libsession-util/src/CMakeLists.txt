
add_library(common INTERFACE)
target_link_libraries(common INTERFACE
    oxenc::oxenc)

target_include_directories(common INTERFACE ../include)
if(WARNINGS_AS_ERRORS)
    target_compile_options(common INTERFACE -Werror)
    message(STATUS "Compiling with fatal warnings (-Werror)")
    if (CMAKE_CXX_COMPILER_ID STREQUAL GNU AND
            CMAKE_CXX_COMPILER_VERSION VERSION_GREATER_EQUAL 11 AND
            CMAKE_CXX_COMPILER_VERSION VERSION_LESS 14)
        # -Wstringop-overflow triggers (falsely) in protobuf in GCC 11/12/13 so disable it there
        # (fingers crossed for 14).
        message(STATUS "Disabling -Werror for buggy GCC stringop-overflow")
        target_compile_options(common INTERFACE -Wno-error=stringop-overflow)
    endif()
endif()


set(export_targets)
macro(add_libsession_util_library name)
    add_library(${name} ${ARGN})

    set_target_properties(
        ${name}
        PROPERTIES
        OUTPUT_NAME session-${name}
        SOVERSION ${LIBSESSION_LIBVERSION})

    libsession_static_bundle(${name})

    list(APPEND export_targets ${name})
endmacro()


if(NOT BUILD_STATIC_DEPS)
    find_package(PkgConfig REQUIRED)

    if(NOT TARGET nettle)
        pkg_check_modules(NETTLE nettle IMPORTED_TARGET REQUIRED)
        add_library(nettle INTERFACE IMPORTED)
        target_link_libraries(nettle INTERFACE PkgConfig::NETTLE)
    endif()
endif()


add_libsession_util_library(crypto
    blinding.cpp
    curve25519.cpp
    ed25519.cpp
    hash.cpp
    multi_encrypt.cpp
    random.cpp
    session_encrypt.cpp
    sodium_array.cpp
    util.cpp
    xed25519.cpp
)

add_libsession_util_library(config
    bt_merge.cpp
    config.cpp
    config/base.cpp
    config/community.cpp
    config/contacts.cpp
    config/convo_info_volatile.cpp
    config/encrypt.cpp
    config/error.c
    config/groups/info.cpp
    config/groups/keys.cpp
    config/groups/members.cpp
    config/internal.cpp
    config/protos.cpp
    config/user_groups.cpp
    config/user_profile.cpp
    fields.cpp
)



target_link_libraries(crypto
    PUBLIC
    common
    PRIVATE
    libsodium::sodium-internal
)

target_link_libraries(config
    PUBLIC
    crypto
    common
    libsession::protos
    PRIVATE
    libsodium::sodium-internal
    libzstd::static
)

if(ENABLE_ONIONREQ)
    add_libsession_util_library(onionreq
        onionreq/builder.cpp
        onionreq/hop_encryption.cpp
        onionreq/key_types.cpp
        onionreq/parser.cpp
        onionreq/response_parser.cpp
    )

    target_link_libraries(onionreq
        PUBLIC
        crypto
        common
        PRIVATE
        nlohmann_json::nlohmann_json
        libsodium::sodium-internal
        nettle
    )
endif()


if(WARNINGS_AS_ERRORS AND NOT USE_LTO AND CMAKE_C_COMPILER_ID STREQUAL "GNU" AND CMAKE_C_COMPILER_VERSION MATCHES "^11\\.")
    # GCC 11 has an overzealous (and false) stringop-overread warning, but only when LTO is off.
    # Um, yeah.
    target_compile_options(config PUBLIC -Wno-error=stringop-overread)
endif()


if(LIBSESSION_UTIL_VERSIONTAG)
    set(PROJECT_VERSION_TAG "${LIBSESSION_UTIL_VERSIONTAG}")
    configure_file("${CMAKE_CURRENT_SOURCE_DIR}/version.c.in" "${CMAKE_CURRENT_BINARY_DIR}/version.c")
else()
    find_package(Git)
    if(EXISTS "${PROJECT_SOURCE_DIR}/.git/index" AND GIT_FOUND)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/version.c"
            COMMAND
            "${CMAKE_COMMAND}"
            "-DGIT=${GIT_EXECUTABLE}"
            "-DPROJECT_VERSION_MAJOR=${PROJECT_VERSION_MAJOR}"
            "-DPROJECT_VERSION_MINOR=${PROJECT_VERSION_MINOR}"
            "-DPROJECT_VERSION_PATCH=${PROJECT_VERSION_PATCH}"
            "-DSRC=${CMAKE_CURRENT_SOURCE_DIR}/version.c.in"
            "-DDEST=${CMAKE_CURRENT_BINARY_DIR}/version.c"
            "-P" "${PROJECT_SOURCE_DIR}/cmake/GenVersion.cmake"
            DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/version.c.in"
            "${PROJECT_SOURCE_DIR}/.git/index")
    else()
        message(STATUS "Git was not found or this is not a git checkout. Setting version tag to 'unknown'")
        set(PROJECT_VERSION_TAG "nogit")
        configure_file("${CMAKE_CURRENT_SOURCE_DIR}/version.c.in" "${CMAKE_CURRENT_BINARY_DIR}/version.c")
    endif()
endif()
add_library(version STATIC version.c)
libsession_static_bundle(version)
target_include_directories(version PRIVATE ../include)
target_link_libraries(common INTERFACE version)


foreach(tgt ${export_targets})
    add_library("libsession::${tgt}" ALIAS "${tgt}")
endforeach()
export(
    TARGETS ${export_targets} common version
    NAMESPACE libsession::
    APPEND FILE libsessionTargets.cmake
)

list(APPEND libsession_export_targets ${export_targets})
set(libsession_export_targets "${libsession_export_targets}" PARENT_SCOPE)
