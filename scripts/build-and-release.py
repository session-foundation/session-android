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


# Number of versions to keep in the fdroid repo. Will remove all the older versions.
KEEP_FDROID_VERSIONS = 4


@dataclass
class BuildResult:
    max_version_code: int
    version_name: str
    apk_paths: list[str]
    bundle_path: str
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

def build_releases(project_root: str, flavor: str, credentials_property_prefix: str, credentials: BuildCredentials, huawei: bool=False) -> BuildResult:
    (keystore_fd, keystore_file) = tempfile.mkstemp(prefix='keystore_', suffix='.jks', dir=build_dir)
    try:
        with os.fdopen(keystore_fd, 'wb') as f:
            f.write(base64.b64decode(credentials.keystore_b64))

        gradle_commands = f"""./gradlew \
                    -P{credentials_property_prefix}_STORE_FILE='{keystore_file}'\
                    -P{credentials_property_prefix}_STORE_PASSWORD='{credentials.keystore_password}' \
                    -P{credentials_property_prefix}_KEY_ALIAS='{credentials.key_alias}' \
                    -P{credentials_property_prefix}_KEY_PASSWORD='{credentials.key_password}'"""
        
        if huawei:
            gradle_commands += ' -Phuawei '

        subprocess.run(f"""{gradle_commands} \
                    assemble{flavor.capitalize()}Release \
                    bundle{flavor.capitalize()}Release --stacktrace""", shell=True, check=True, cwd=project_root)

        apk_output_dir = os.path.join(project_root, f'app/build/outputs/apk/{flavor}/release')

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
                            bundle_path=os.path.join(project_root, f'app/build/outputs/bundle/{flavor}Release/session-{version_name}-{flavor}-release.aab'))
        
    finally:
        print(f'Cleaning up keystore file: {keystore_file}')
        os.remove(keystore_file)


project_root = os.path.dirname(sys.path[0])
build_dir = os.path.join(project_root, 'build')
credentials_file_path = os.path.join(project_root, 'release-creds.toml')
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
    all_apk_versions_and_ctime = [(re.search(r'session-(.+?)-', os.path.basename(name)).group(1), os.path.getctime(name))
                            for name in glob.glob(os.path.join(fdroid_workspace, 'repo/session-*-arm64-v8a.apk'))]
    # Sort by ctime DESC
    all_apk_versions_and_ctime.sort(key=lambda x: x[1], reverse=True)
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

    # Create Pull Request for releases
    print('Creating a pull request...')
    subprocess.run(f'''\
                   gh pr create --base master \
                    --title "Release {build.version_name}" \
                    -R session-foundation/session-fdroid \
                    --body "This is an automated release preparation for Release {build.version_name}. Human beings are still required to approve and merge this PR."\
                    ''', shell=True, check=True, cwd=fdroid_workspace)
    

# Make sure gh command is available
if shutil.which('gh') is None:
    print('`gh` command not found. It is required to automate fdroid releases. Please install it from https://cli.github.com/', file=sys.stderr)
    sys.exit(1)

# Make sure credentials file exists
if not os.path.isfile(credentials_file_path):
    print(f'Credentials file not found at {credentials_file_path}. You should ask the project maintainer for the file.', file=sys.stderr)
    sys.exit(1)

with open(credentials_file_path, 'rb') as f:
    credentials = tomllib.load(f)

# Make sure build folder exists
if not os.path.isdir(build_dir):
    os.makedirs(build_dir)

print("Building play releases...")
play_build_result = build_releases(
    project_root=project_root, 
    flavor='play',
    credentials=BuildCredentials(credentials['build']['play']),
    credentials_property_prefix='SESSION'
    )

print("Updating fdroid repo...")
update_fdroid(build=play_build_result, creds=BuildCredentials(credentials['fdroid']), fdroid_workspace=os.path.join(fdroid_repo_path, 'fdroid'))

print("Building huawei releases...")
huawei_build_result = build_releases(
    project_root=project_root, 
    flavor='huawei',
    credentials=BuildCredentials(credentials['build']['huawei']),
    credentials_property_prefix='SESSION_HUAWEI',
    huawei=True
    )

# If the a github release draft exists, upload the apks to the release
try:
    release_info = json.loads(subprocess.check_output(f'gh release view --json isDraft {play_build_result.version_name}', shell=True, cwd=project_root))
    if release_info['draft'] == True:
        print(f'Uploading build artifact to the release {play_build_result.version_name} draft...')
        files_to_upload = [*play_build_result.apk_paths,
                           play_build_result.bundle_path,
                           *huawei_build_result.apk_paths]
        upload_commands = ['gh', 'release', 'upload', play_build_result.version_name, '--clobber', *files_to_upload]
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