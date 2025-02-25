local submodule_commands = [
  'git fetch --tags',
  'git submodule update --init --recursive --depth=1 --jobs=4',
];
local submodules = {
  name: 'submodules',
  image: 'drone/git',
  commands: submodule_commands,
};

local apt_get_quiet = 'apt-get -o=Dpkg::Use-Pty=0 -q';

local libngtcp2_deps = ['libgnutls28-dev', 'libprotobuf-dev'];

local default_deps_nocxx = [
  'nlohmann-json3-dev',
] + libngtcp2_deps;

local default_deps = ['g++'] + default_deps_nocxx;

local default_test_deps = libngtcp2_deps;

local docker_base = 'registry.oxen.rocks/';

local debian_backports(distro, pkgs) = [
  'echo "deb http://deb.debian.org/debian ' + distro + '-backports main" >/etc/apt/sources.list.d/' + distro + '-backports.list',
  'eatmydata ' + apt_get_quiet + ' update',
  'eatmydata ' + apt_get_quiet + ' install -y ' + std.join(' ', std.map(function(x) x + '/' + distro + '-backports', pkgs)),
];

// Do something on a debian-like system
local debian_pipeline(name,
                      image,
                      arch='amd64',
                      deps=default_deps,
                      oxen_repo=true,
                      kitware_repo=''/* ubuntu codename, if wanted */,
                      allow_fail=false,
                      cmake_pkg='cmake',
                      build=['echo "Error: drone build argument not set"', 'exit 1'],
                      extra_setup=[],
                      extra_steps=[])
      = {
  kind: 'pipeline',
  type: 'docker',
  name: name,
  platform: { arch: arch },
  steps: [
    submodules,
    {
      name: 'build',
      image: image,
      pull: 'always',
      [if allow_fail then 'failure']: 'ignore',
      environment: { SSH_KEY: { from_secret: 'SSH_KEY' }, WINEDEBUG: '-all' },
      commands: [
        'echo "Building on ${DRONE_STAGE_MACHINE}"',
        'echo "man-db man-db/auto-update boolean false" | debconf-set-selections',
        apt_get_quiet + ' update',
        apt_get_quiet + ' install -y eatmydata',
      ] + (
        if oxen_repo then [
          'eatmydata ' + apt_get_quiet + ' install --no-install-recommends -y lsb-release',
          'cp utils/deb.oxen.io.gpg /etc/apt/trusted.gpg.d',
          'echo deb http://deb.oxen.io $$(lsb_release -sc) main >/etc/apt/sources.list.d/oxen.list',
          'eatmydata ' + apt_get_quiet + ' update',
        ] else []
      ) + (
        if kitware_repo != '' then [
          'eatmydata ' + apt_get_quiet + ' install --no-install-recommends -y curl ca-certificates',
          'curl -sS https://apt.kitware.com/keys/kitware-archive-latest.asc | gpg --dearmor - >/usr/share/keyrings/kitware-archive-keyring.gpg',
          'echo "deb [signed-by=/usr/share/keyrings/kitware-archive-keyring.gpg] https://apt.kitware.com/ubuntu/ ' + kitware_repo + ' main" >/etc/apt/sources.list.d/kitware.list',
          'eatmydata ' + apt_get_quiet + ' update',
        ] else []
      ) + extra_setup + [
        'eatmydata ' + apt_get_quiet + ' dist-upgrade -y',
        'eatmydata ' + apt_get_quiet + ' install --no-install-recommends -y ' + cmake_pkg + ' make git ccache ca-certificates ' + std.join(' ', deps),
      ] + build,
    },
  ] + extra_steps,
};

// Regular build on a debian-like system:
local debian_build(name,
                   image,
                   arch='amd64',
                   deps=default_deps,
                   test_deps=default_test_deps,
                   build_type='Release',
                   lto=false,
                   werror=true,
                   cmake_extra='',
                   shared_libs=true,
                   jobs=6,
                   tests=true,
                   oxen_repo=true,
                   kitware_repo=''/* ubuntu codename, if wanted */,
                   extra_setup=[],
                   allow_fail=false)
      = debian_pipeline(
  name,
  image,
  arch=arch,
  deps=deps,
  oxen_repo=oxen_repo,
  kitware_repo=kitware_repo,
  allow_fail=allow_fail,
  build=[
    'mkdir build',
    'cd build',
    'cmake .. -DCMAKE_CXX_FLAGS=-fdiagnostics-color=always -DCMAKE_BUILD_TYPE=' + build_type + ' ' +
    (if werror then '-DWARNINGS_AS_ERRORS=ON ' else '') +
    (if shared_libs then '-DBUILD_SHARED_LIBS=ON ' else '') +
    '-DUSE_LTO=' + (if lto then 'ON ' else 'OFF ') +
    '-DWITH_TESTS=' + (if tests then 'ON ' else 'OFF ') +
    cmake_extra,
    'make VERBOSE=1 -j' + jobs,
  ],
  extra_setup=extra_setup,
  extra_steps=(if tests then
                 [{
                   name: 'tests',
                   image: image,
                   pull: 'always',
                   [if allow_fail then 'failure']: 'ignore',
                   commands:
                     [apt_get_quiet + ' install -y eatmydata'] + (
                       if std.length(test_deps) > 0 then (
                         if oxen_repo then [
                           'eatmydata ' + apt_get_quiet + ' install --no-install-recommends -y lsb-release',
                           'cp utils/deb.oxen.io.gpg /etc/apt/trusted.gpg.d',
                           'echo deb http://deb.oxen.io $$(lsb_release -sc) main >/etc/apt/sources.list.d/oxen.list',
                         ] else []
                       ) + [
                         'eatmydata ' + apt_get_quiet + ' update',
                         'eatmydata ' + apt_get_quiet + ' dist-upgrade -y',
                         'eatmydata ' + apt_get_quiet + ' install --no-install-recommends -y ' + std.join(' ', test_deps),
                       ] else []
                     ) + [
                       'cd build',
                       './tests/testAll --colour-mode ansi -d yes',
                     ],
                 }] else [])
);
// windows cross compile on debian
local windows_cross_pipeline(name,
                             image,
                             arch='amd64',
                             build_type='Release',
                             lto=false,
                             werror=false,
                             cmake_extra='',
                             jobs=6,
                             tests=true,
                             winarch='x86-64',
                             allow_fail=false)
      = debian_pipeline(
  name,
  image,
  arch=arch,
  allow_fail=allow_fail,
  deps=[
    'g++-mingw-w64-' + winarch + '-posix',
    'wine',
  ],
  build=[
    'mkdir build',
    'cd build',
    'cmake .. -DCMAKE_CXX_FLAGS=-fdiagnostics-color=always -DCMAKE_BUILD_TYPE=' + build_type + ' ' +
    (if werror then '-DWARNINGS_AS_ERRORS=ON ' else '') +
    '-DUSE_LTO=' + (if lto then 'ON ' else 'OFF ') +
    '-DWITH_TESTS=' + (if tests then 'ON ' else 'OFF ') +
    '-DCMAKE_TOOLCHAIN_FILE=../cmake/mingw-' + winarch + '-toolchain.cmake ' +
    cmake_extra,
    'make VERBOSE=1 -j' + jobs,
  ],
  extra_steps=(if tests then
                 [{
                   name: 'tests',
                   image: image,
                   pull: 'always',
                   [if allow_fail then 'failure']: 'ignore',
                   environment: { WINEDEBUG: '-all' },
                   commands: [
                     apt_get_quiet + ' install -y --no-install-recommends wine64',
                     'cd build',
                     'wine-stable ./tests/testAll.exe --colour-mode ansi -d yes',
                   ],
                 }] else [])
);

local clang(version) = debian_build(
  'Debian sid/clang-' + version,
  docker_base + 'debian-sid-clang',
  deps=['clang-' + version] + default_deps_nocxx,
  cmake_extra='-DCMAKE_C_COMPILER=clang-' + version + ' -DCMAKE_CXX_COMPILER=clang++-' + version + ' '
);

local full_llvm(version) = debian_build(
  'Debian sid/llvm-' + version,
  docker_base + 'debian-sid-clang',
  deps=['clang-' + version, ' lld-' + version, ' libc++-' + version + '-dev', 'libc++abi-' + version + '-dev']
       + default_deps_nocxx,
  shared_libs=false,
  cmake_extra='-DCMAKE_C_COMPILER=clang-' + version +
              ' -DCMAKE_CXX_COMPILER=clang++-' + version +
              ' -DCMAKE_CXX_FLAGS="-stdlib=libc++ -fcolor-diagnostics" ' +
              std.join(' ', [
                '-DCMAKE_' + type + '_LINKER_FLAGS=-fuse-ld=lld-' + version
                for type in ['EXE', 'MODULE', 'SHARED']
              ])
);

// Macos build
local mac_pipeline(name,
                   arch='amd64',
                   allow_fail=false,
                   build=['echo "Error: drone build argument not set"', 'exit 1'],
                   extra_steps=[])
      = {
  kind: 'pipeline',
  type: 'exec',
  name: name,
  platform: { os: 'darwin', arch: arch },
  steps: [
    { name: 'submodules', commands: submodule_commands },
    {
      name: 'build',
      environment: { SSH_KEY: { from_secret: 'SSH_KEY' } },
      commands: [
        'echo "Building on ${DRONE_STAGE_MACHINE}"',
        // If you don't do this then the C compiler doesn't have an include path containing
        // basic system headers.  WTF apple:
        'export SDKROOT="$(xcrun --sdk macosx --show-sdk-path)"',
      ] + build,
    },
  ] + extra_steps,
};
local mac_builder(name,
                  arch='amd64',
                  build_type='Release',
                  werror=true,
                  lto=false,
                  cmake_extra='',
                  jobs=6,
                  tests=true,
                  allow_fail=false)
      = mac_pipeline(name, arch=arch, allow_fail=allow_fail, build=[
  'mkdir build',
  'cd build',
  'cmake .. -DCMAKE_CXX_FLAGS=-fcolor-diagnostics -DCMAKE_BUILD_TYPE=' + build_type + ' ' +
  (if werror then '-DWARNINGS_AS_ERRORS=ON ' else '') +
  '-DBUILD_SHARED_LIBS=ON ' +
  '-DUSE_LTO=' + (if lto then 'ON ' else 'OFF ') +
  '-DWITH_TESTS=' + (if tests then 'ON ' else 'OFF ') +
  cmake_extra,
  'VERBOSE=1 make -j' + jobs,
], extra_steps=
                     (if tests then
                        [{
                          name: 'tests',
                          [if allow_fail then 'failure']: 'ignore',
                          commands: [
                            'cd build',
                            './tests/testAll --colour-mode ansi -d yes',
                          ],
                        }] else []));

local static_build(name,
                   image,
                   archive_name,
                   arch='amd64',
                   build_type='Release',
                   lto=true,
                   deps=default_deps,
                   oxen_repo=false,
                   kitware_repo=''/* ubuntu codename, if wanted */,
                   cmake_extra='',
                   allow_fail=false,
                   jobs=6)
      = debian_pipeline(
  name,
  image,
  arch=arch,
  deps=deps,
  oxen_repo=oxen_repo,
  allow_fail=allow_fail,
  build=[
    'export JOBS=' + jobs,
    './utils/static-bundle.sh build ' + archive_name + ' -DSTATIC_LIBSTD=ON ' + cmake_extra,
    'cd build && ../utils/ci/drone-static-upload.sh',
  ]
);

[
  {
    name: 'lint check',
    kind: 'pipeline',
    type: 'docker',
    steps: [{
      name: 'build',
      image: docker_base + 'lint',
      pull: 'always',
      commands: [
        'echo "Building on ${DRONE_STAGE_MACHINE}"',
        apt_get_quiet + ' update',
        apt_get_quiet + ' install -y eatmydata',
        'eatmydata ' + apt_get_quiet + ' install --no-install-recommends -y git clang-format-15 jsonnet',
        './utils/ci/drone-format-verify.sh',
      ],
    }],
  },

  {
    name: 'API Documentation',
    kind: 'pipeline',
    type: 'docker',
    steps: [{
      name: 'build',
      image: 'node:19-bullseye',
      pull: 'always',
      environment: { SSH_KEY: { from_secret: 'SSH_KEY' } },
      commands: [
        'echo "Building on ${DRONE_STAGE_MACHINE}"',
        apt_get_quiet + ' update',
        apt_get_quiet + ' install -y python3-requests rsync',
        'npm i docsify-cli docsify-themeable docsify-katex@1.4.4 katex marked@4',
        'cd docs/api/',
        'export NODE_PATH=node_modules',
        'make',
        '../../utils/ci/drone-docs-upload.sh',
      ],
    }],
    trigger: { branch: ['dev'], event: ['push'] },
  },

  // Various debian builds
  debian_build('Debian sid', docker_base + 'debian-sid'),
  debian_build('Debian sid/Debug', docker_base + 'debian-sid', build_type='Debug'),
  debian_build('Debian testing', docker_base + 'debian-testing'),
  clang(16),
  full_llvm(16),
  debian_build('Debian stable (i386)', docker_base + 'debian-stable/i386'),
  debian_build('Debian 11', docker_base + 'debian-bullseye', extra_setup=debian_backports('bullseye', ['cmake'])),
  debian_build('Ubuntu latest', docker_base + 'ubuntu-rolling'),
  debian_build('Ubuntu LTS', docker_base + 'ubuntu-lts'),

  // ARM builds (ARM64 and armhf)
  debian_build('Debian sid (ARM64)', docker_base + 'debian-sid', arch='arm64', jobs=4),
  debian_build('Debian stable (armhf)', docker_base + 'debian-stable/arm32v7', arch='arm64', jobs=4),

  // Macos builds:
  mac_builder('macOS Intel (Release)'),
  mac_builder('macOS Arm64 (Release)', arch='arm64'),
  mac_builder('macOS Arm64 (Debug)', arch='arm64', build_type='Debug'),

  // Static lib builds
  static_build('Static Linux/amd64', docker_base + 'debian-stable', 'libsession-util-linux-amd64-TAG.tar.xz'),
  static_build('Static Linux/i386', docker_base + 'debian-stable', 'libsession-util-linux-i386-TAG.tar.xz'),
  static_build('Static Linux/arm64', docker_base + 'debian-stable', 'libsession-util-linux-arm64-TAG.tar.xz', arch='arm64'),
  static_build('Static Linux/armhf', docker_base + 'debian-stable/arm32v7', 'libsession-util-linux-armhf-TAG.tar.xz', arch='arm64'),
  static_build('Static Windows x64',
               docker_base + 'debian-win32-cross',
               'libsession-util-windows-x64-TAG.zip',
               deps=['g++-mingw-w64-x86-64-posix'],
               cmake_extra='-DCMAKE_CXX_FLAGS=-fdiagnostics-color=always -DCMAKE_TOOLCHAIN_FILE=../cmake/mingw-x86-64-toolchain.cmake'),
  static_build('Static Windows x86',
               docker_base + 'debian-win32-cross',
               'libsession-util-windows-x86-TAG.zip',
               deps=['g++-mingw-w64-i686-posix'],
               allow_fail=true,
               cmake_extra='-DCMAKE_CXX_FLAGS=-fdiagnostics-color=always -DCMAKE_TOOLCHAIN_FILE=../cmake/mingw-i686-toolchain.cmake'),
  debian_pipeline(
    'Static Android',
    docker_base + 'android',
    build=[
      'export JOBS=6',
      'export NDK=/usr/lib/android-ndk',
      './utils/android.sh libsession-util-android-TAG.tar.xz',
      'cd build-android && ../utils/ci/drone-static-upload.sh',
    ]
  ),

  mac_pipeline('Static macOS', build=[
    'export JOBS=6',
    './utils/macos.sh',
    'cd build-macos && ../utils/ci/drone-static-upload.sh',
  ]),

  mac_pipeline('Static iOS', arch='arm64', build=[
    'export JOBS=6',
    './utils/ios.sh libsession-util-ios-TAG',
    'cd build-ios && ../utils/ci/drone-static-upload.sh',
  ]),
]
