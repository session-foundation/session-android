
set(LIBSESSION_STATIC_BUNDLE_LIBS "" CACHE INTERNAL "list of libs to go into the static bundle lib")

# Call as:
#
#     libsession_static_bundle(target [target2 ...])
#
# to append the given target(s) to the list of libraries that will be combined to make the static
# bundled libsession-util.a.
function(libsession_static_bundle)
    list(APPEND LIBSESSION_STATIC_BUNDLE_LIBS "${ARGN}")
    list(REMOVE_DUPLICATES LIBSESSION_STATIC_BUNDLE_LIBS)
    set(LIBSESSION_STATIC_BUNDLE_LIBS "${LIBSESSION_STATIC_BUNDLE_LIBS}" CACHE INTERNAL "")
endfunction()
