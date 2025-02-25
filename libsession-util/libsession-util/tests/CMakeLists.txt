add_subdirectory(Catch2)

add_executable(testAll
    test_blinding.cpp
    test_bt_merge.cpp
    test_bugs.cpp
    test_compression.cpp
    test_config_userprofile.cpp
    test_config_user_groups.cpp
    test_configdata.cpp
    test_config_contacts.cpp
    test_config_convo_info_volatile.cpp
    test_curve25519.cpp
    test_ed25519.cpp
    test_encrypt.cpp
    test_group_keys.cpp
    test_group_info.cpp
    test_group_members.cpp
    test_hash.cpp
    test_multi_encrypt.cpp
    test_onionreq.cpp
    test_proto.cpp
    test_random.cpp
    test_session_encrypt.cpp
    test_xed25519.cpp
)


target_link_libraries(testAll PRIVATE
    libsession::config
    libsodium::sodium-internal
    Catch2::Catch2WithMain
)

if (ENABLE_ONIONREQ)
    target_link_libraries(testAll PRIVATE libsession::onionreq)
endif()

add_custom_target(check COMMAND testAll)

add_executable(swarm-auth-test EXCLUDE_FROM_ALL swarm-auth-test.cpp)
target_link_libraries(swarm-auth-test PRIVATE config)

if(STATIC_BUNDLE)
    add_executable(static-bundle-test static_bundle.cpp)
    target_include_directories(static-bundle-test PUBLIC ../include)
    target_link_libraries(static-bundle-test PRIVATE "${PROJECT_BINARY_DIR}/libsession-util.a" oxenc::oxenc)
    add_dependencies(static-bundle-test session-util)
endif()
