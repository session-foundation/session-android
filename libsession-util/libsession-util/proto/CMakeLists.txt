
libsession_static_bundle(protobuf::libprotobuf-lite)


add_library(protos
    SessionProtos.pb.cc
    WebSocketResources.pb.cc)
target_include_directories(protos PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(protos PUBLIC protobuf::libprotobuf-lite)
set_target_properties(
    protos PROPERTIES
    OUTPUT_NAME session-protos
    SOVERSION ${LIBSESSION_LIBVERSION})

libsession_static_bundle(protos)

add_library(libsession::protos ALIAS protos)
export(
    TARGETS protos
    NAMESPACE libsession::
    FILE libsessionTargets.cmake
)
list(APPEND libsession_export_targets protos)
set(libsession_export_targets "${libsession_export_targets}" PARENT_SCOPE)


add_custom_target(regen-protobuf
    protoc --cpp_out=. SessionProtos.proto WebSocketResources.proto
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
)
