# cmake bits to do a full static build, downloading and building all dependencies.

# Most of these are CACHE STRINGs so that you can override them using -DWHATEVER during cmake
# invocation to override.

set(LOCAL_MIRROR "" CACHE STRING "local mirror path/URL for lib downloads")

set(GMP_VERSION 6.3.0 CACHE STRING "gmp version")
set(GMP_MIRROR ${LOCAL_MIRROR} https://gmplib.org/download/gmp
    CACHE STRING "gmp mirror(s)")
set(GMP_SOURCE gmp-${GMP_VERSION}.tar.xz)
set(GMP_HASH SHA512=e85a0dab5195889948a3462189f0e0598d331d3457612e2d3350799dba2e244316d256f8161df5219538eb003e4b5343f989aaa00f96321559063ed8c8f29fd2
    CACHE STRING "gmp source hash")

set(NETTLE_VERSION 3.9.1 CACHE STRING "nettle version")
set(NETTLE_MIRROR ${LOCAL_MIRROR} https://ftp.gnu.org/gnu/nettle
    CACHE STRING "nettle mirror(s)")
set(NETTLE_SOURCE nettle-${NETTLE_VERSION}.tar.gz)
set(NETTLE_HASH SHA512=5939c4b43cf9ff6c6272245b85f123c81f8f4e37089fa4f39a00a570016d837f6e706a33226e4bbfc531b02a55b2756ff312461225ed88de338a73069e031ced
    CACHE STRING "nettle source hash")


include(ExternalProject)

set(DEPS_DESTDIR ${CMAKE_BINARY_DIR}/static-deps)
set(DEPS_SOURCEDIR ${CMAKE_BINARY_DIR}/static-deps-sources)

file(MAKE_DIRECTORY ${DEPS_DESTDIR}/include)

add_library(libsession-external-libs INTERFACE IMPORTED GLOBAL)
target_include_directories(libsession-external-libs SYSTEM BEFORE INTERFACE ${DEPS_DESTDIR}/include)

set(deps_cc "${CMAKE_C_COMPILER}")
set(deps_cxx "${CMAKE_CXX_COMPILER}")


function(expand_urls output source_file)
  set(expanded)
  foreach(mirror ${ARGN})
    list(APPEND expanded "${mirror}/${source_file}")
  endforeach()
  set(${output} "${expanded}" PARENT_SCOPE)
endfunction()

function(add_static_target target ext_target libname)
  add_library(${target} STATIC IMPORTED GLOBAL)
  add_dependencies(${target} ${ext_target})
  target_link_libraries(${target} INTERFACE libsession-external-libs)
  set_target_properties(${target} PROPERTIES
    IMPORTED_LOCATION ${DEPS_DESTDIR}/lib/${libname}
  )
  if(ARGN)
      target_link_libraries(${target} INTERFACE ${ARGN})
  endif()
  libsession_static_bundle(${target})
endfunction()



set(cross_host "")
set(cross_rc "")
if(CMAKE_CROSSCOMPILING)
  if(APPLE AND NOT ARCH_TRIPLET AND APPLE_TARGET_TRIPLE)
    set(ARCH_TRIPLET "${APPLE_TARGET_TRIPLE}")
  endif()
  set(cross_host "--host=${ARCH_TRIPLET}")
  if (ARCH_TRIPLET MATCHES mingw AND CMAKE_RC_COMPILER)
    set(cross_rc "WINDRES=${CMAKE_RC_COMPILER}")
  endif()
endif()
if(ANDROID)
  set(android_toolchain_suffix linux-android)
  set(android_compiler_suffix linux-android${ANDROID_PLATFORM_LEVEL})
  if(CMAKE_ANDROID_ARCH_ABI MATCHES x86_64)
    set(cross_host "--host=x86_64-linux-android")
    set(android_compiler_prefix x86_64)
    set(android_compiler_suffix linux-android${ANDROID_PLATFORM_LEVEL})
    set(android_toolchain_prefix x86_64)
    set(android_toolchain_suffix linux-android)
  elseif(CMAKE_ANDROID_ARCH_ABI MATCHES x86)
    set(cross_host "--host=i686-linux-android")
    set(android_compiler_prefix i686)
    set(android_compiler_suffix linux-android${ANDROID_PLATFORM_LEVEL})
    set(android_toolchain_prefix i686)
    set(android_toolchain_suffix linux-android)
  elseif(CMAKE_ANDROID_ARCH_ABI MATCHES armeabi-v7a)
    set(cross_host "--host=armv7a-linux-androideabi")
    set(android_compiler_prefix armv7a)
    set(android_compiler_suffix linux-androideabi${ANDROID_PLATFORM_LEVEL})
    set(android_toolchain_prefix arm)
    set(android_toolchain_suffix linux-androideabi)
  elseif(CMAKE_ANDROID_ARCH_ABI MATCHES arm64-v8a)
    set(cross_host "--host=aarch64-linux-android")
    set(android_compiler_prefix aarch64)
    set(android_compiler_suffix linux-android${ANDROID_PLATFORM_LEVEL})
    set(android_toolchain_prefix aarch64)
    set(android_toolchain_suffix linux-android)
  else()
    message(FATAL_ERROR "unknown android arch: ${CMAKE_ANDROID_ARCH_ABI}")
  endif()
  set(deps_cc "${ANDROID_TOOLCHAIN_ROOT}/bin/${android_compiler_prefix}-${android_compiler_suffix}-clang")
  set(deps_cxx "${ANDROID_TOOLCHAIN_ROOT}/bin/${android_compiler_prefix}-${android_compiler_suffix}-clang++")
  set(deps_ld "${ANDROID_TOOLCHAIN_ROOT}/bin/${android_compiler_prefix}-${android_toolchain_suffix}-ld")
  set(deps_ranlib "${ANDROID_TOOLCHAIN_ROOT}/bin/${android_toolchain_prefix}-${android_toolchain_suffix}-ranlib")
  set(deps_ar "${ANDROID_TOOLCHAIN_ROOT}/bin/${android_toolchain_prefix}-${android_toolchain_suffix}-ar")
endif()

set(deps_CFLAGS "-O2")
set(deps_CXXFLAGS "-O2")

if(CMAKE_C_COMPILER_LAUNCHER)
  set(deps_cc "${CMAKE_C_COMPILER_LAUNCHER} ${deps_cc}")
endif()
if(CMAKE_CXX_COMPILER_LAUNCHER)
  set(deps_cxx "${CMAKE_CXX_COMPILER_LAUNCHER} ${deps_cxx}")
endif()

if(WITH_LTO)
  set(deps_CFLAGS "${deps_CFLAGS} -flto")
endif()

if(APPLE AND CMAKE_OSX_DEPLOYMENT_TARGET)
  if(SDK_NAME)
    set(deps_CFLAGS "${deps_CFLAGS} -m${SDK_NAME}-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
    set(deps_CXXFLAGS "${deps_CXXFLAGS} -m${SDK_NAME}-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
  else()
    set(deps_CFLAGS "${deps_CFLAGS} -mmacosx-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
    set(deps_CXXFLAGS "${deps_CXXFLAGS} -mmacosx-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
  endif()
endif()

if(_winver)
  set(deps_CFLAGS "${deps_CFLAGS} -D_WIN32_WINNT=${_winver}")
  set(deps_CXXFLAGS "${deps_CXXFLAGS} -D_WIN32_WINNT=${_winver}")
endif()


if("${CMAKE_GENERATOR}" STREQUAL "Unix Makefiles")
  set(_make $(MAKE))
else()
  set(_make make)
endif()


# Builds a target; takes the target name (e.g. "readline") and builds it in an external project with
# target name suffixed with `_external`.  Its upper-case value is used to get the download details
# (from the variables set above).  The following options are supported and passed through to
# ExternalProject_Add if specified.  If omitted, these defaults are used:
set(build_def_DEPENDS "")
set(build_def_PATCH_COMMAND "")
set(build_def_CONFIGURE_COMMAND ./configure ${cross_host} --disable-shared --prefix=${DEPS_DESTDIR} --with-pic
    "CC=${deps_cc}" "CXX=${deps_cxx}" "CFLAGS=${deps_CFLAGS}" "CXXFLAGS=${deps_CXXFLAGS}" ${cross_rc})
set(build_def_CONFIGURE_EXTRA "")
set(build_def_BUILD_COMMAND ${_make})
set(build_def_INSTALL_COMMAND ${_make} install)
set(build_def_BUILD_BYPRODUCTS ${DEPS_DESTDIR}/lib/lib___TARGET___.a ${DEPS_DESTDIR}/include/___TARGET___.h)

function(build_external target)
  set(options DEPENDS PATCH_COMMAND CONFIGURE_COMMAND CONFIGURE_EXTRA BUILD_COMMAND INSTALL_COMMAND BUILD_BYPRODUCTS)
  cmake_parse_arguments(PARSE_ARGV 1 arg "" "" "${options}")
  foreach(o ${options})
    if(NOT DEFINED arg_${o})
      set(arg_${o} ${build_def_${o}})
    endif()
  endforeach()
  string(REPLACE ___TARGET___ ${target} arg_BUILD_BYPRODUCTS "${arg_BUILD_BYPRODUCTS}")

  string(TOUPPER "${target}" prefix)
  expand_urls(urls ${${prefix}_SOURCE} ${${prefix}_MIRROR})
  set(extract_ts)
  if(NOT CMAKE_VERSION VERSION_LESS 3.24)
      set(extract_ts DOWNLOAD_EXTRACT_TIMESTAMP ON)
  endif()
  ExternalProject_Add("${target}_external"
    DEPENDS ${arg_DEPENDS}
    BUILD_IN_SOURCE ON
    PREFIX ${DEPS_SOURCEDIR}
    URL ${urls}
    URL_HASH ${${prefix}_HASH}
    DOWNLOAD_NO_PROGRESS ON
    PATCH_COMMAND ${arg_PATCH_COMMAND}
    CONFIGURE_COMMAND ${arg_CONFIGURE_COMMAND} ${arg_CONFIGURE_EXTRA}
    BUILD_COMMAND ${arg_BUILD_COMMAND}
    INSTALL_COMMAND ${arg_INSTALL_COMMAND}
    BUILD_BYPRODUCTS ${arg_BUILD_BYPRODUCTS}
    EXCLUDE_FROM_ALL ON
    ${extract_ts}
  )
endfunction()


set(apple_cflags_arch)
set(apple_cxxflags_arch)
set(apple_ldflags_arch)
set(gmp_build_host "${cross_host}")
if(APPLE AND CMAKE_CROSSCOMPILING)
    if(gmp_build_host MATCHES "^(.*-.*-)ios([0-9.]+)(-.*)?$")
        set(gmp_build_host "${CMAKE_MATCH_1}darwin${CMAKE_MATCH_2}${CMAKE_MATCH_3}")
    endif()
    if(gmp_build_host MATCHES "^(.*-.*-.*)-simulator$")
        set(gmp_build_host "${CMAKE_MATCH_1}")
    endif()

    set(apple_arch)
    if(ARCH_TRIPLET MATCHES "^(arm|aarch)64.*")
        set(apple_arch "arm64")
    elseif(ARCH_TRIPLET MATCHES "^x86_64.*")
        set(apple_arch "x86_64")
    else()
        message(FATAL_ERROR "Don't know how to specify -arch for GMP for ${ARCH_TRIPLET} (${APPLE_TARGET_TRIPLE})")
    endif()

    set(apple_cflags_arch " -arch ${apple_arch}")
    set(apple_cxxflags_arch " -arch ${apple_arch}")
    if(CMAKE_OSX_DEPLOYMENT_TARGET)
      if (SDK_NAME)
        set(apple_ldflags_arch " -m${SDK_NAME}-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
      elseif(CMAKE_OSX_DEPLOYMENT_TARGET)
        set(apple_ldflags_arch " -mmacosx-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
      endif()
    endif()
    set(apple_ldflags_arch "${apple_ldflags_arch} -arch ${apple_arch}")

    if(CMAKE_OSX_SYSROOT)
      foreach(f c cxx ld)
        set(apple_${f}flags_arch "${apple_${f}flags_arch} -isysroot ${CMAKE_OSX_SYSROOT}")
      endforeach()
    endif()
elseif(gmp_build_host STREQUAL "")
    set(gmp_build_host "--build=${CMAKE_LIBRARY_ARCHITECTURE}")
endif()

if(ENABLE_ONIONREQ)
    build_external(gmp
        CONFIGURE_COMMAND ./configure ${gmp_build_host} --disable-shared --prefix=${DEPS_DESTDIR} --with-pic
            "CC=${deps_cc}" "CXX=${deps_cxx}" "CFLAGS=${deps_CFLAGS}${apple_cflags_arch}" "CXXFLAGS=${deps_CXXFLAGS}${apple_cxxflags_arch}"
            "LDFLAGS=${apple_ldflags_arch}" ${cross_rc} CC_FOR_BUILD=cc CPP_FOR_BUILD=cpp
    )
    add_static_target(gmp gmp_external libgmp.a)

    build_external(nettle
        CONFIGURE_COMMAND ./configure ${gmp_build_host} --disable-shared --prefix=${DEPS_DESTDIR} --libdir=${DEPS_DESTDIR}/lib
            --with-pic --disable-openssl
            "CC=${deps_cc}" "CXX=${deps_cxx}"
            "CFLAGS=${deps_CFLAGS}${apple_cflags_arch}" "CXXFLAGS=${deps_CXXFLAGS}${apple_cxxflags_arch}"
            "CPPFLAGS=-I${DEPS_DESTDIR}/include"
            "LDFLAGS=-L${DEPS_DESTDIR}/lib${apple_ldflags_arch}"

        DEPENDS gmp_external
        BUILD_BYPRODUCTS
        ${DEPS_DESTDIR}/lib/libnettle.a
        ${DEPS_DESTDIR}/lib/libhogweed.a
        ${DEPS_DESTDIR}/include/nettle/version.h
    )
    add_static_target(nettle nettle_external libnettle.a gmp)
    add_static_target(hogweed nettle_external libhogweed.a nettle)
endif()

link_libraries(-static-libstdc++)
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
    link_libraries(-static-libgcc)
endif()
if(MINGW)
    link_libraries(-Wl,-Bstatic -lpthread)
endif()
