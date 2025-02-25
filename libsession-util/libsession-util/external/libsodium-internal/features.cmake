include(TestBigEndian)

TEST_BIG_ENDIAN(NATIVE_BIG_ENDIAN)

target_compile_definitions(${LIB_NAME} PRIVATE $<$<NOT:$<BOOL:${NATIVE_BIG_ENDIAN}>>:NATIVE_LITTLE_ENDIAN>)

include(CheckCSourceCompiles)

set(save_cmake_req_defs "${CMAKE_REQUIRED_DEFINITIONS}")

if(NOT NATIVE_BIG_ENDIAN)
    set(CMAKE_REQUIRED_DEFINITIONS "-DNATIVE_LITTLE_ENDIAN")
else()
    set(CMAKE_REQUIRED_DEFINITIONS "")
endif()


function(check_for_definition DEFINE src)
    check_c_source_compiles("${src}" ${DEFINE})
    target_compile_definitions(${LIB_NAME} PRIVATE $<$<BOOL:${${DEFINE}}>:${DEFINE}>)
endfunction()

function(check_for_public_definition DEFINE src)
    check_c_source_compiles("${src}" ${DEFINE})
    target_compile_definitions(${LIB_NAME} PUBLIC $<$<BOOL:${${DEFINE}}>:${DEFINE}>)
endfunction()

check_for_public_definition(HAVE_TI_MODE [[
#if !defined(__clang__) && !defined(__GNUC__) && !defined(__SIZEOF_INT128__)
# error mode(TI) is a gcc extension, and __int128 is not available
#endif
#if defined(__clang__) && !defined(__x86_64__) && !defined(__aarch64__)
# error clang does not properly handle the 128-bit type on 32-bit systems
#endif
#ifndef NATIVE_LITTLE_ENDIAN
# error libsodium currently expects a little endian CPU for the 128-bit type
#endif
#ifdef __EMSCRIPTEN__
# error emscripten currently doesn't support some operations on integers larger than 64 bits
#endif
#include <stddef.h>
#include <stdint.h>
#if defined(__SIZEOF_INT128__)
typedef unsigned __int128 uint128_t;
#else
typedef unsigned uint128_t __attribute__((mode(TI)));
#endif
void fcontract(uint128_t *t) {
  *t += 0x8000000000000 - 1;
  *t *= *t;
  *t >>= 84;
}

int
main (void)
{

(void) fcontract;

  ;
  return 0;
}
]])

check_for_definition(HAVE_CPUID [[
int
main (void)
{

unsigned int cpu_info[4];
__asm__ __volatile__ ("xchgl %%ebx, %k1; cpuid; xchgl %%ebx, %k1" :
                      "=a" (cpu_info[0]), "=&r" (cpu_info[1]),
                      "=c" (cpu_info[2]), "=d" (cpu_info[3]) :
                      "0" (0U), "2" (0U));

  ;
  return 0;
}
]])

check_for_definition(HAVE_AVX_ASM [[
int
main (void)
{

#if defined(__amd64) || defined(__amd64__) || defined(__x86_64__)
# if defined(__CYGWIN__) || defined(__MINGW32__) || defined(__MINGW64__) || defined(_WIN32) || defined(_WIN64)
#  error Windows x86_64 calling conventions are not supported yet
# endif
/* neat */
#else
# error !x86_64
#endif
__asm__ __volatile__ ("vpunpcklqdq %xmm0,%xmm13,%xmm0");

  ;
  return 0;
}
]])


check_for_definition(HAVE_AMD64_ASM [[
int
main (void)
{

#if defined(__amd64) || defined(__amd64__) || defined(__x86_64__)
# if defined(__CYGWIN__) || defined(__MINGW32__) || defined(__MINGW64__) || defined(_WIN32) || defined(_WIN64)
#  error Windows x86_64 calling conventions are not supported yet
# endif
/* neat */
#else
# error !x86_64
#endif
unsigned char i = 0, o = 0, t;
__asm__ __volatile__ ("pxor %%xmm12, %%xmm6 \n"
                      "movb (%[i]), %[t] \n"
                      "addb %[t], (%[o]) \n"
                      : [t] "=&r"(t)
                      : [o] "D"(&o), [i] "S"(&i)
                      : "memory", "flags", "cc");

  ;
  return 0;
}
]])


check_for_definition(HAVE_INLINE_ASM [[
int
main (void)
{

#if defined(__amd64) || defined(__amd64__) || defined(__x86_64__)
# if defined(__CYGWIN__) || defined(__MINGW32__) || defined(__MINGW64__) || defined(_WIN32) || defined(_WIN64)
#  error Windows x86_64 calling conventions are not supported yet
# endif
/* neat */
#else
# error !x86_64
#endif
__asm__ __volatile__ ("vpunpcklqdq %xmm0,%xmm13,%xmm0");

  ;
  return 0;
}
]])

check_for_definition(HAVE_C_VARARRAYS [[
/* Test for VLA support.  This test is partly inspired
		  from examples in the C standard.  Use at least two VLA
		  functions to detect the GCC 3.4.3 bug described in:
		  https://lists.gnu.org/archive/html/bug-gnulib/2014-08/msg00014.html
		  */
	       #ifdef __STDC_NO_VLA__
		syntax error;
	       #else
		 extern int n;
		 int B[100];
		 int fvla (int m, int C[m][m]);

		 int
		 simple (int count, int all[static count])
		 {
		   return all[count - 1];
		 }

		 int
		 fvla (int m, int C[m][m])
		 {
		   typedef int VLA[m][m];
		   VLA x;
		   int D[m];
		   static int (*q)[m] = &B;
		   int (*s)[n] = q;
		   return C && &x[0][0] == &D[0] && &D[0] == s[0];
		 }
	       #endif

int
main (void)
{

  ;
  return 0;
}
]])

check_for_definition(HAVE_ALLOCA [[
#include <stdlib.h>
#include <stddef.h>
#ifndef alloca
# ifdef __GNUC__
#  define alloca __builtin_alloca
# elif defined _MSC_VER
#  include <malloc.h>
#  define alloca _alloca
# else
#  ifdef  __cplusplus
extern "C"
#  endif
void *alloca (size_t);
# endif
#endif

int
main (void)
{
char *p = (char *) alloca (1);
				    if (p) return 0;
  ;
  return 0;
}
]])



set(CMAKE_REQUIRED_DEFINITIONS "${save_cmake_req_defs}")
