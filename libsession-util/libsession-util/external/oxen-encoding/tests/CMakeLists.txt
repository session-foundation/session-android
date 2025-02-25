
enable_testing()



add_subdirectory(Catch2)

set(TEST_SRC
    main.cpp
    test_bt.cpp
    test_encoding.cpp
    test_endian.cpp
)

add_executable(tests ${TEST_SRC})

find_package(Threads)

target_link_libraries(tests Catch2::Catch2 oxenc)

set_target_properties(tests PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

add_custom_target(check COMMAND tests)

