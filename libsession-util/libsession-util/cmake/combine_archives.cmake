function(combine_archives output_archive dep_target)
  set(FULL_OUTPUT_PATH ${CMAKE_CURRENT_BINARY_DIR}/lib${output_archive}.a)

  if(NOT APPLE)
    set(mri_file ${CMAKE_CURRENT_BINARY_DIR}/${output_archive}.mri)
    set(mri_content "create ${FULL_OUTPUT_PATH}\n")
    foreach(in_archive ${ARGN})
        string(APPEND mri_content "addlib $<TARGET_FILE:${in_archive}>\n")
    endforeach()
    string(APPEND mri_content "save\nend\n")
    file(GENERATE OUTPUT ${mri_file} CONTENT "${mri_content}")

    add_custom_command(
        OUTPUT ${FULL_OUTPUT_PATH}
        DEPENDS ${mri_file} ${ARGN}
        COMMAND ar -M < ${mri_file})
  else()
    set(merge_libs)
    foreach(in_archive ${ARGN})
      list(APPEND merge_libs $<TARGET_FILE:${in_archive}>)
    endforeach()
    add_custom_command(
        OUTPUT ${FULL_OUTPUT_PATH}
        DEPENDS ${mri_file} ${ARGN}
        COMMAND /usr/bin/libtool -static -o ${FULL_OUTPUT_PATH} ${merge_libs})
  endif()
  add_custom_target(${output_archive} DEPENDS ${FULL_OUTPUT_PATH})
endfunction(combine_archives)
