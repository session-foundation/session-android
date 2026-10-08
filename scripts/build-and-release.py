#!/usr/bin/env python3

import subprocess
import json
import os
import sys
import shutil
import re
import tomllib
from dataclasses import dataclass
import tempfile
import base64
import string
import glob
import argparse

from _keyring import get_secret


# Number of versions to keep in the fdroid repo. Will remove all the older versions.
KEEP_FDROID_VERSIONS = 4


@dataclass
class BuildResult:
    max_version_code: int
    version_name: str
    apk_paths: list[str]
    bundle_path: str | None
    package_id: str

@dataclass
class BuildCredentials:
    keystore_b64: str
    keystore_password: str
    key_alias: str
    key_password: str

    def __init__(self, credentials: dict):
        self.keystore_b64 = credentials['keystore'].strip()
        self.keystore_password = credentials['keystore_password']
        self.key_alias = credentials['key_alias']
        self.key_password = credentials['key_password']

def build_releases(project_root: str, 
                   flavor: str, 
                   credentials_property_prefix: str, 
                   credentials: BuildCredentials, 
                   extra_gradle_opts: str = '',
                   build_type: string = 'release',
                   build_bundle: bool = False,
                   split_apks: bool = False,
                   libsession_util_path: str = None) -> BuildResult:
    (keystore_fd, keystore_file) = tempfile.mkstemp(prefix='keystore_', suffix='.jks', dir=build_dir)
    try:
        with os.fdopen(keystore_fd, 'wb') as f:
            f.write(base64.b64decode(credentials.keystore_b64))

        # Build the glue (and its native libsession-util) from source at this path instead of the
        # published AAR; settings.gradle.kts reads this system property (see its comment there).
        lib_source_opt = (f" -Dsession.libsession_util.project.path='{libsession_util_path}'"
                          if libsession_util_path else "")
        # Pass signing secrets via ORG_GRADLE_PROJECT_* env vars, NOT -P command-line args: argv is
        # world-readable (`ps`, /proc/<pid>/cmdline) and gets echoed into subprocess exceptions on
        # failure, whereas /proc/<pid>/environ is owner-only. Gradle maps ORG_GRADLE_PROJECT_<name>
        # to the `<name>` project property, so the app's signingConfig reads these unchanged.
        gradle_env = {
            **os.environ,
            f'ORG_GRADLE_PROJECT_{credentials_property_prefix}_STORE_FILE': keystore_file,
            f'ORG_GRADLE_PROJECT_{credentials_property_prefix}_STORE_PASSWORD': credentials.keystore_password,
            f'ORG_GRADLE_PROJECT_{credentials_property_prefix}_KEY_ALIAS': credentials.key_alias,
            f'ORG_GRADLE_PROJECT_{credentials_property_prefix}_KEY_PASSWORD': credentials.key_password,
        }
        gradle_commands = f"./gradlew{lib_source_opt} {extra_gradle_opts}"
        
        if build_bundle:
            bundle_path = os.path.join(project_root, f'app/build/outputs/bundle/{flavor}{build_type.capitalize()}/app-{flavor}-{build_type}.aab')
            subprocess.run(f"""{gradle_commands} -PsplitApks=false \
                    bundle{flavor.capitalize()}{build_type.capitalize()} --stacktrace""", shell=True, check=True, cwd=project_root, env=gradle_env)
        else:
            bundle_path = None

        subprocess.run(f"""{gradle_commands} \
                    assemble{flavor.capitalize()}{build_type.capitalize()} -PsplitApks={str(split_apks).lower()} --stacktrace""", shell=True, check=True, cwd=project_root, env=gradle_env)

        apk_output_dir = os.path.join(project_root, f'app/build/outputs/apk/{flavor}/{build_type}')

        with open(os.path.join(apk_output_dir, 'output-metadata.json')) as f:
            play_outputs = json.load(f)

        apks = [os.path.join(apk_output_dir, f['outputFile']) for f in play_outputs['elements']]
        max_version_code = max(map(lambda element: element['versionCode'], play_outputs['elements']))
        package_id = play_outputs['applicationId']
        version_name = play_outputs['elements'][0]['versionName']

        print('Max version code is: ', max_version_code)

        return BuildResult(max_version_code=max_version_code,
                            apk_paths=apks, 
                            package_id=package_id, 
                            version_name=version_name,
                            bundle_path=bundle_path)
        
    finally:
        print(f'Cleaning up keystore file: {keystore_file}')
        os.remove(keystore_file)


project_root = os.path.dirname(sys.path[0])
build_dir = os.path.join(project_root, 'build')
RELEASE_CREDS_KEYRING_NAME = 'release-creds'
fdroid_repo_path = os.path.join(build_dir, 'fdroidrepo')

def detect_android_sdk() -> str:
    sdk_dir = os.environ.get('ANDROID_HOME')
    if sdk_dir is None:
        with open(os.path.join(project_root, 'local.properties')) as f:
            matched = next(re.finditer(r'^sdk.dir=(.+?)$', f.read(), re.MULTILINE), None)
            sdk_dir = matched.group(1) if matched else None

    if sdk_dir is None or not os.path.isdir(sdk_dir):
        raise Exception('Android SDK not found. Please set ANDROID_HOME or add sdk.dir to local.properties')
            
    return sdk_dir


def update_fdroid(build: BuildResult, fdroid_workspace: str, creds: BuildCredentials):
    # Check if there's a git repo at the fdroid repo path by running git status
    try:
        subprocess.check_call(f'git -C {fdroid_repo_path} status', shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.check_call(f'git fetch --depth=1', shell=True, cwd=fdroid_workspace)
        print(f'Found fdroid git repo at {fdroid_repo_path}')
    except subprocess.CalledProcessError:
        print(f'No fdroid git repo found at {fdroid_repo_path}. Cloning using gh.')
        subprocess.run(f'gh repo clone session-foundation/session-fdroid {fdroid_repo_path} -- -b master --depth=1', shell=True, check=True)

    # Create a branch for the release
    print(f'Creating a branch for the fdroid release: {build.version_name}')
    try:
        branch_name = f'release/{build.version_name}'
        # Clean and switch to master before doing anything
        subprocess.check_call(f'git reset --hard HEAD && git checkout master', shell=True, cwd=fdroid_workspace, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        # Delete the existing local branch regardlessly
        subprocess.run(f'git branch -D {branch_name}', check=False, shell=True, cwd=fdroid_workspace)
        
        # Check if the remote branch already exists, or we need to create a new one
        try:
            subprocess.check_call(f'git ls-remote --exit-code origin refs/heads/{branch_name}', shell=True, cwd=fdroid_workspace, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            print(f'Branch {branch_name} already exists. Checking out...')
            subprocess.check_call(f'git checkout {branch_name}', shell=True, cwd=fdroid_workspace)
        except subprocess.CalledProcessError:
            print(f'Branch {branch_name} not found. Creating a new branch.')
            subprocess.check_call(f'git checkout -b {branch_name} origin/master', shell=True, cwd=fdroid_workspace)
        
    except subprocess.CalledProcessError:
        print(f'Failed to create a branch for the release. ')
        sys.exit(1)

    # Copy the apks to the fdroid repo
    for apk in build.apk_paths:
        if apk.endswith('-universal.apk'):
            print('Skipping universal apk:', apk)
            continue

        dst = os.path.join(fdroid_workspace, 'repo/' + os.path.basename(apk))
        print('Copying', apk, 'to', dst)
        shutil.copy(apk, dst)

    # Make sure there are only last three versions of APKs
    all_apk_versions_and_ctime = [(re.search(r'session-(.+?)-', os.path.basename(name)).group(1), os.path.getmtime(name))
                            for name in glob.glob(os.path.join(fdroid_workspace, 'repo/session-*-arm64-v8a*.apk'))]
    
    # Sort by ctime DESC
    all_apk_versions_and_ctime.sort(key=lambda x: x[0], reverse=True)

    # Remove all but the last three versions
    for version, _ in all_apk_versions_and_ctime[KEEP_FDROID_VERSIONS:]:
        for apk in glob.glob(os.path.join(fdroid_workspace, f'repo/session-{version}-*.apk')):
            print('Removing old apk:', apk)
            os.remove(apk)

    # Update the metadata file
    metadata_file = os.path.join(fdroid_workspace, f'metadata/{build.package_id}.yml')
    with open(f'{metadata_file}.tpl', 'r') as template_file:
        metadata_template = string.Template(template_file.read())
        metadata_contents = metadata_template.substitute({
            'currentVersionCode': build.max_version_code,
        })
    with open(metadata_file, 'w') as file:
        file.write(metadata_contents)

    [keystore_fd, keystore_path] = tempfile.mkstemp(prefix='fdroid_keystore_', suffix='.p12', dir=build_dir)
    config_file_path = os.path.join(fdroid_workspace, 'config.yml')

    try:
        android_sdk = detect_android_sdk()
        with os.fdopen(keystore_fd, 'wb') as f:
            f.write(base64.b64decode(creds.keystore_b64))

        # Read the config template and create a config file
        with open(f'{config_file_path}.tpl') as config_template_file:
            config_template = string.Template(config_template_file.read())
            with open(config_file_path, 'w') as f:
                f.write(config_template.substitute({
                    'keystore_file': keystore_path,
                    'keystore_pass': creds.keystore_password,
                    'repo_keyalias': creds.key_alias,
                    'key_pass': creds.key_password,
                    'android_sdk': android_sdk
                }))
        

        # Run fdroid update
        print("Running fdroid update...")
        environs = os.environ.copy()
        subprocess.run('fdroid update', shell=True, check=True, cwd=fdroid_workspace, env=environs)
    finally:
        print(f'Cleaning up...')
        if os.path.exists(metadata_file):
            os.remove(metadata_file)

        if os.path.exists(keystore_path):
            os.remove(keystore_path)
            
        if os.path.exists(config_file_path):
            os.remove(config_file_path)
    
    # Commit the changes
    print('Committing the changes...')
    subprocess.run(f'git add . && git commit -am "Prepare for release {build.version_name}"', shell=True, check=True, cwd=fdroid_workspace)

    # Push the branch explicitly to origin (session-foundation/session-fdroid) first, so
    # `gh pr create` has nothing to push and won't prompt. `gh repo clone` adds an `upstream`
    # remote (oxen-io), so with two remotes gh otherwise can't decide where to push the head.
    print('Pushing the release branch...')
    subprocess.run(f'git push -u origin {branch_name} --force-with-lease', shell=True, check=True, cwd=fdroid_workspace)

    # Create Pull Request for releases (--head names the already-pushed branch => no prompt)
    print('Creating a pull request...')
    subprocess.run(f'''\
                   gh pr create --base master --head {branch_name} \
                    --title "Release {build.version_name}" \
                    -R session-foundation/session-fdroid \
                    --body "This is an automated release preparation for Release {build.version_name}. Human beings are still required to approve and merge this PR."\
                    ''', shell=True, check=True, cwd=fdroid_workspace)

parser = argparse.ArgumentParser(
    prog='build-and-release.py',
    description='Build and release script for Session Android'
 )

parser.add_argument('--build-only', action='store_true', help='If set, will only build APKs and skip all upload/fdroid actions')
parser.add_argument('--play-only', action='store_true', help='Build only the signed play AAB (for a dev/test upload to the internal track); skips the fdroid and huawei flavors and all upload/PR steps')
parser.add_argument('--build-type', help='Build with specified build type. Default: release', default = 'release')
parser.add_argument('--libsession', metavar='PATH', help="Build libsession-util-android (and its native libsession-util) from source at this path (the glue repo root), instead of the published AAR. Passed to Gradle as -Dsession.libsession_util.project.path; overrides any local.properties setting.")

args = parser.parse_args()

# Resolve to an absolute path: settings.gradle.kts resolves a relative path against the app's
# rootDir, so an absolute path is unambiguous regardless of where the glue is checked out.
lib_util_path = os.path.abspath(args.libsession) if args.libsession else None

# Make sure gh command is available (not needed for a play-only build)
if not args.play_only and shutil.which('gh') is None:
    print('`gh` command not found. It is required to automate fdroid releases. Please install it from https://cli.github.com/', file=sys.stderr)
    sys.exit(1)

# Make sure fdroid command is available (not needed for a play-only build)
if not args.play_only and shutil.which('fdroid') is None:
    print('`fdroid` command not found. Install fdroidserver via your system package manager:\n'
          '  Debian/Ubuntu:  apt install fdroidserver\n'
          '  Homebrew:       brew install fdroidserver\n'
          '  MacPorts:       port install fdroidserver\n'
          'Other methods: https://f-droid.org/docs/Installing_the_Server_and_Repo_Tools/',
          file=sys.stderr)
    sys.exit(1)

# Load signing credentials (a TOML blob) from the OS keyring, so they never sit
# on disk in plaintext. Store them once with:
#   python3 scripts/_keyring.py set release-creds < release-creds.toml
# then delete the plaintext file.
credentials = tomllib.loads(get_secret(
    RELEASE_CREDS_KEYRING_NAME,
    what='Release signing credentials (release-creds.toml contents)'))

# Make sure build folder exists
if not os.path.isdir(build_dir):
    os.makedirs(build_dir)

print("Building play releases...")
play_build_result = build_releases(
    project_root=project_root, 
    flavor='play',
    credentials=BuildCredentials(credentials['build']['play']),
    credentials_property_prefix='SESSION',
    build_type=args.build_type,
    build_bundle=True,
    split_apks=True,
    libsession_util_path=lib_util_path,
    )

# A play-only build is just the signed AAB for a dev/test upload to the internal
# track, so skip the other flavors and every upload/PR step below.
if args.play_only:
    print('\n=====================')
    print('Build result (play only):')
    for apk in play_build_result.apk_paths:
        print(f'\t{apk}')
    print(f'\t{play_build_result.bundle_path}')
    print('=====================')
    sys.exit(0)

print("Building fdroid releases...")
fdroid_build_result = build_releases(
    project_root=project_root,
    flavor='fdroid',
    credentials=BuildCredentials(credentials['build']['play']),
    credentials_property_prefix='SESSION',
    build_type=args.build_type,
    build_bundle=False,
    split_apks=True,
    libsession_util_path=lib_util_path,
    )

if not args.build_only:
    print("Updating fdroid repo...")
    update_fdroid(build=fdroid_build_result, creds=BuildCredentials(credentials['fdroid']), fdroid_workspace=os.path.join(fdroid_repo_path, 'fdroid'))

print("Building huawei releases...")
huawei_build_result = build_releases(
    project_root=project_root, 
    flavor='huawei',
    credentials=BuildCredentials(credentials['build']['huawei']),
    credentials_property_prefix='SESSION_HUAWEI',
    extra_gradle_opts='-Phuawei',
    build_type=args.build_type,
    build_bundle=False,
    split_apks=False,
    libsession_util_path=lib_util_path,
    )

# If the a github release draft exists, upload the apks to the release
if not args.build_only:
    try:
        release_info = json.loads(subprocess.check_output(f'gh release view -R session-foundation/session-android --json isDraft {play_build_result.version_name}', shell=True, cwd=project_root))
        if release_info['isDraft'] == True:
            print(f'Uploading build artifact to the release {play_build_result.version_name} draft...')
            files_to_upload = [*play_build_result.apk_paths,
                               play_build_result.bundle_path,
                               *huawei_build_result.apk_paths]
            upload_commands = ['gh', 'release', 'upload', '-R', 'session-foundation/session-android', play_build_result.version_name, '--clobber', *files_to_upload]
            subprocess.run(upload_commands, shell=False, cwd=project_root, check=True)

            print('Successfully uploaded these files to the draft release: ')
            for file in files_to_upload:
                print(file)
        else:
            print(f'Release {play_build_result.version_name} not a draft. Skipping upload of apks to the release.')
    except subprocess.CalledProcessError:
        print(f'{play_build_result.version_name} has not had a release draft created. Skipping upload of apks to the release.')


print('\n=====================')
print('Build result: ')
print('Play:')
for apk in play_build_result.apk_paths:
    print(f'\t{apk}')
print(f'\t{play_build_result.bundle_path}')

print('Huawei:')
for apk in huawei_build_result.apk_paths:
    print(f'\t{apk}')
print('=====================')
