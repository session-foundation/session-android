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

@dataclass
class BuildResult:
    max_version_code: int
    apk_paths: list[str]
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

def build_releases(project_root: str, flavor: str, credentials_property_prefix: str, credentials: BuildCredentials) -> BuildResult:
    (keystore_fd, keystore_file) = tempfile.mkstemp(prefix='keystore_', suffix='.jks', dir=os.path.join(project_root, 'build'))
    try:
        with os.fdopen(keystore_fd, 'wb') as f:
            f.write(base64.b64decode(credentials.keystore_b64))

        subprocess.run(f"""./gradlew \
                    -P{credentials_property_prefix}_STORE_FILE='{keystore_file}'\
                    -P{credentials_property_prefix}_STORE_PASSWORD='{credentials.keystore_password}' \
                    -P{credentials_property_prefix}_KEY_ALIAS='{credentials.key_alias}' \
                    -P{credentials_property_prefix}_KEY_PASSWORD='{credentials.key_password}' \
                    assemble{flavor.capitalize()}Release \
                    bundle{flavor.capitalize()}Release --stacktrace""", shell=True, check=True, cwd=project_root)

        apk_output_dir = os.path.join(project_root, f'app/build/outputs/apk/{flavor}/release')

        with open(os.path.join(apk_output_dir, 'output-metadata.json')) as f:
            play_outputs = json.load(f)

        apks = [os.path.join(apk_output_dir, f['outputFile']) for f in play_outputs['elements']]
        max_version_code = max(map(lambda element: element['versionCode'], play_outputs['elements']))
        package_id = play_outputs['applicationId']

        print('Will upload the following apks to f-droid:')
        for apk in apks:
            print(apk)

        print('Max version code is: ', max_version_code)

        return BuildResult(max_version_code=max_version_code, apk_paths=apks, package_id=package_id)
        
    finally:
        print(f'Cleaning up keystore file: {keystore_file}')
        os.remove(keystore_file)


# Parent of this script file
project_root = os.path.dirname(sys.path[0])

credentials_file_path = os.path.join(project_root, 'release-creds.toml')

with open(credentials_file_path, 'rb') as f:
    credentials = tomllib.load(f)

print("Building play releases...")
#subprocess.run('./gradlew assemblePlayRelease bundlePlayRelease', shell=True, check=True, cwd=project_root)


result = build_releases(
    project_root=project_root, 
    flavor='play',
    credentials=BuildCredentials(credentials['build']['play']),
    credentials_property_prefix='SESSION'
    )

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
    # Copy the apks to the fdroid repo
    for apk in build.apk_paths:
        if apk.endswith('-universal.apk'):
            print('Skipping universal apk:', apk)
            continue

        dst = os.path.join(fdroid_workspace, 'repo/' + os.path.basename(apk))
        print('Copying', apk, 'to', dst)
        shutil.copy(apk, dst)

    # Update the metadata file
    metadata_file = os.path.join(fdroid_workspace, f'metadata/{build.package_id}.yml')
    with open(metadata_file, 'r') as file:
        metadata_contents = file.read()
    # Replace `CurrentVersionCode: xxxx` with the new version code
    metadata_contents = re.sub(r'^CurrentVersionCode: .+$', f'CurrentVersionCode: {build.max_version_code}', metadata_contents)
    with open(metadata_file, 'w') as file:
        file.write(metadata_contents)

    [keystore_fd, keystore_path] = tempfile.mkstemp(prefix='fdroid_keystore_', suffix='.p12', dir=os.path.join(project_root, 'build'))

    try:
        with os.fdopen(keystore_fd, 'wb') as f:
            f.write(base64.b64decode(creds.keystore_b64))

        # Run fdroid update
        print("Running fdroid update...")
        environs = os.environ.copy()
        environs['ANDROID_HOME'] = detect_android_sdk()
        environs['FDROID_KEYSTORE_FILE'] = keystore_path
        environs['FDROID_KEYSTORE_PASSWORD'] = creds.keystore_password
        environs['FDROID_KEY_PASSWORD'] = creds.key_password
        environs['FDROID_KEY_ALIAS'] = creds.key_alias
        subprocess.run('fdroid update -v', shell=True, check=True, cwd=fdroid_workspace, env=environs)
    finally:
        print(f'Cleaning up keystore file: {keystore_path}')
        # os.remove(keystore_path)

    

update_fdroid(build=result, creds=BuildCredentials(credentials['fdroid']), fdroid_workspace='/Users/fanchao/Downloads/fdroidrepo')

#print("Building huawei releases...")
#subprocess.run('./gradlew -Phuawei assembleHuaweiRelease', shell=True, check=True, cwd=project_root)