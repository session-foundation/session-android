# cmake script to generate a version file via a configure_file after determining the current git
# commit and tagged status.
#
# The should be invoked via something like the following:
#
#    find_package(Git REQUIRED)
#    add_custom_command(
#        OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/version.c"
#        COMMAND
#        "${CMAKE_COMMAND}"
#        "-DGIT=${GIT_EXECUTABLE}"
#        "-DPROJECT_VERSION_MAJOR=${PROJECT_VERSION_MAJOR}"
#        "-DPROJECT_VERSION_MINOR=${PROJECT_VERSION_MINOR}"
#        "-DPROJECT_VERSION_PATCH=${PROJECT_VERSION_PATCH}"
#        "-DSRC=${CMAKE_CURRENT_SOURCE_DIR}/version.c.in"
#        "-DDEST=${CMAKE_CURRENT_BINARY_DIR}/version.c"
#        "-P" "${PROJECT_SOURCE_DIR}/cmake/GenVersion.cmake"
#        DEPENDS
#        "${CMAKE_CURRENT_SOURCE_DIR}/version.c.in"
#        "${PROJECT_SOURCE_DIR}/.git/index")
#
# to dynamically create build/.../version.c from src/.../version.c.in and have it get properly
# recreated as part of the build whenever either version.c.in or the current git commit change.
#


execute_process(
    COMMAND "${GIT}" rev-parse --short HEAD
    RESULT_VARIABLE git_result
    OUTPUT_VARIABLE git_commit
    OUTPUT_STRIP_TRAILING_WHITESPACE)

if(git_result)
    message(WARNING "Failed to get current git commit; setting version tag to 'unknown'")
    set(PROJECT_VERSION_TAG "unknown")
else()
    message(STATUS "Setting version tag to current git commit ${git_commit}")

	execute_process(COMMAND "${GIT}" tag --list --points-at HEAD
        RESULT_VARIABLE git_result
        OUTPUT_VARIABLE git_tag
        OUTPUT_STRIP_TRAILING_WHITESPACE)

    if(git_tag)
        message(STATUS "${git_commit} is tagged (${git_tag}); tagging version as 'release'")
        set(vfull "v${PROJECT_VERSION_MAJOR}.${PROJECT_VERSION_MINOR}.${PROJECT_VERSION_PATCH}")
        set(PROJECT_VERSION_TAG "release")

        if (NOT git_tag STREQUAL "${vfull}")
            message(FATAL_ERROR "This commit is tagged, but the tag (${git_tag}) does not match the project version (${vfull})!")
        endif()
    else()
        message(STATUS "Did not find a git tag for ${git_commit}; tagging version with the commit hash")
        set(PROJECT_VERSION_TAG "${git_commit}")
    endif()
endif()

configure_file("${SRC}" "${DEST}" @ONLY)
