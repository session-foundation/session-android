#!/usr/bin/env python3
"""Manage Session's Google Play release from the command line.

Talks to the Google Play Developer API using a service-account key read from the
OS keyring (see _keyring.py). Subcommands:

  status                             show each track's releases (version code, status, rollout %)
  upload [AAB]                       upload an AAB to a track
  rollout {FRACTION|complete|halt}   change the current staged rollout on a track (no re-upload)
  share [APK|AAB]                    upload to Internal App Sharing, print an install link (no
                                     versionCode consumed; the fast iterate-with-Billing loop)

Prerequisites:
  - apt install python3-google-auth python3-googleapi   (or the pip equivalents)
  - the `keyring` library + an unlocked keyring, with the key stored once:
        python3 scripts/_keyring.py set play-service-account < service-account.json
    (then delete the JSON file)

Note: Play has no API to auto-ramp a staged rollout, and with managed publishing ON a
submitted release is reviewed but held until you click Publish in the Play Console
(there is no API for that final publish). Use `rollout` (e.g. from cron) to ramp manually.

Examples:
    scripts/play-store.py status
    scripts/play-store.py upload                                  # draft on the internal track
    scripts/play-store.py upload --track production --submit      # full rollout (submitted)
    scripts/play-store.py upload --track production --rollout 0.1 # start a 10% staged rollout
    scripts/play-store.py rollout --track production 0.5          # bump the staged rollout to 50%
    scripts/play-store.py rollout --track production complete     # go to 100%
    scripts/play-store.py rollout --track production halt         # pause the rollout
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

from _keyring import get_secret

KEYRING_NAME = "play-service-account"
DEFAULT_PACKAGE = "network.loki.messenger"
DEFAULT_AAB = "app/build/outputs/bundle/playRelease/app-play-release.aab"
SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
TRACKS = ["internal", "alpha", "beta", "production"]


def play_service():
    """Build an authenticated androidpublisher client (imports + keyring read here)."""
    try:
        from google.oauth2 import service_account
        from googleapiclient.discovery import build
    except ImportError:
        sys.exit("Missing Google API libraries. Install them, e.g.:\n"
                 "  apt install python3-google-auth python3-googleapi")
    try:
        info = json.loads(get_secret(KEYRING_NAME, what="Play service-account JSON key"))
    except json.JSONDecodeError as e:
        sys.exit(f"Stored Play service-account secret is not valid JSON: {e}")
    creds = service_account.Credentials.from_service_account_info(info, scopes=SCOPES)
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False)


def in_edit(service, pkg, fn, *, commit):
    """Run fn(edit_id) inside a Play edit; commit on success, always abandon on failure."""
    edit_id = service.edits().insert(body={}, packageName=pkg).execute()["id"]
    try:
        result = fn(edit_id)
    except BaseException:
        try:
            service.edits().delete(packageName=pkg, editId=edit_id).execute()
        except Exception:
            pass
        raise
    if commit:
        service.edits().commit(packageName=pkg, editId=edit_id).execute()
    else:
        service.edits().delete(packageName=pkg, editId=edit_id).execute()
    return result


def fmt_release(rel):
    status = rel.get("status", "?")
    frac = rel.get("userFraction")
    frac_s = f" @ {frac:.0%}" if frac is not None else ""
    vcs = ",".join(str(v) for v in (rel.get("versionCodes") or []))
    name = rel.get("name", "")
    return f"{status}{frac_s}  versionCodes=[{vcs}]  {name}".rstrip()


def cmd_status(pkg, args):
    service = play_service()
    data = in_edit(service, pkg,
                   lambda eid: service.edits().tracks().list(packageName=pkg, editId=eid).execute(),
                   commit=False)
    tracks = [t for t in data.get("tracks", []) if t.get("releases")]
    if not tracks:
        print("No track releases found.")
        return
    for t in tracks:
        print(f"{t['track']}:")
        for rel in t["releases"]:
            print(f"    {fmt_release(rel)}")


def cmd_upload(pkg, args):
    if not os.path.isfile(args.aab):
        sys.exit(f"AAB not found: {args.aab}\nBuild it first (build-and-release.py) or pass the path.")

    if args.rollout is not None:
        if not (0.0 < args.rollout < 1.0):
            sys.exit("--rollout must be strictly between 0 and 1 (use --submit for a full rollout).")
        status, desc = "inProgress", f"staged rollout to {args.rollout:.0%} (submitted)"
    elif args.submit:
        status, desc = "completed", "full rollout (submitted)"
    else:
        status, desc = "draft", "draft, not submitted"

    print(f"AAB:     {args.aab}\nTrack:   {args.track}\nStatus:  {status} -- {desc}")
    if args.dry_run:
        print("\nDry run -- not uploading.")
        return

    service = play_service()
    from googleapiclient.http import MediaFileUpload

    def fn(eid):
        media = MediaFileUpload(args.aab, mimetype="application/octet-stream", resumable=True)
        print("Uploading bundle (this can take a while)...")
        vc = service.edits().bundles().upload(
            packageName=pkg, editId=eid, media_body=media).execute()["versionCode"]
        print(f"Uploaded versionCode {vc}.")
        release = {"versionCodes": [str(vc)], "status": status}
        if status == "inProgress":
            release["userFraction"] = args.rollout
        service.edits().tracks().update(packageName=pkg, editId=eid, track=args.track,
                                        body={"releases": [release]}).execute()
        return vc

    vc = in_edit(service, pkg, fn, commit=True)
    if status == "draft":
        print(f"\nDone. versionCode {vc} on '{args.track}' as a DRAFT -- submit it in the console.")
    else:
        print(f"\nDone. versionCode {vc} submitted on '{args.track}' ({desc}).\n"
              f"With managed publishing on, it is held for your manual Publish in the console.")


def cmd_rollout(pkg, args):
    if args.action == "complete":
        new_status, new_fraction = "completed", None
    elif args.action == "halt":
        new_status, new_fraction = "halted", None
    else:
        try:
            new_fraction = float(args.action)
        except ValueError:
            sys.exit("rollout target must be a fraction like 0.25, or 'complete', or 'halt'.")
        if not (0.0 < new_fraction < 1.0):
            sys.exit("rollout fraction must be strictly between 0 and 1 (use 'complete' for 100%).")
        new_status = "inProgress"

    service = play_service()

    def get_active(eid):
        track = service.edits().tracks().get(packageName=pkg, editId=eid, track=args.track).execute()
        return track.get("releases", [])

    if args.dry_run:
        rels = in_edit(service, pkg, get_active, commit=False)
        print(f"Current releases on '{args.track}':")
        for r in rels:
            print(f"    {fmt_release(r)}")
        target = new_status + (f" @ {new_fraction:.0%}" if new_fraction is not None else "")
        print(f"\nDry run -- would set the active rollout to: {target}")
        return

    def fn(eid):
        active = [r for r in get_active(eid) if r.get("status") in ("inProgress", "halted")]
        if not active:
            sys.exit(f"No in-progress/halted rollout on track '{args.track}' to change.\n"
                     f"Run `status` to check, or `upload --rollout` to start one.")
        rel = active[0]
        rel["status"] = new_status
        if new_status == "inProgress":
            rel["userFraction"] = new_fraction
        else:
            rel.pop("userFraction", None)
        service.edits().tracks().update(packageName=pkg, editId=eid, track=args.track,
                                        body={"releases": [rel]}).execute()
        return rel

    rel = in_edit(service, pkg, fn, commit=True)
    print(f"Done. Track '{args.track}' release now: {fmt_release(rel)}")


def _sdk_root():
    return (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            or os.path.expanduser("~/Android/Sdk"))


def _adb():
    return shutil.which("adb") or os.path.join(_sdk_root(), "platform-tools", "adb")


def _emulator_bin():
    return shutil.which("emulator") or os.path.join(_sdk_root(), "emulator", "emulator")


def _running_emulators(adb):
    """Serials of emulators currently in the 'device' (booted + authorized) state."""
    out = subprocess.run([adb, "devices"], capture_output=True, text=True, check=True).stdout
    emus = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[0].startswith("emulator-") and parts[1] == "device":
            emus.append(parts[0])
    return emus


def _avd_is_play_store(name):
    """True/False/None for whether local AVD `name` is a Play Store image. The AVD config is the
    source of truth: a plain google_apis image ships a non-functional com.android.vending stub, so a
    `pm list packages` check can't tell them apart."""
    if not name:
        return None
    avd_home = os.path.expanduser(os.environ.get("ANDROID_AVD_HOME") or "~/.android/avd")
    config = os.path.join(avd_home, f"{name}.avd", "config.ini")
    ini = os.path.join(avd_home, f"{name}.ini")  # points at the real .avd dir when it has been relocated
    if os.path.isfile(ini):
        with open(ini) as f:
            for line in f:
                if line.startswith("path="):
                    config = os.path.join(line.split("=", 1)[1].strip(), "config.ini")
                    break
    if not os.path.isfile(config):
        return None
    text = open(config).read().replace(" ", "")
    if "PlayStore.enabled" in text:
        return "PlayStore.enabled=true" in text
    return "google_apis_playstore" in text


def _running_emulator_avd(adb, serial):
    try:
        out = subprocess.run([adb, "-s", serial, "emu", "avd", "name"],
                             capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    return next((ln.strip() for ln in out.splitlines() if ln.strip() and ln.strip() != "OK"), None)


def _launch_play_store_avd(adb):
    """No emulator running: find a Play Store AVD, launch it detached, wait for boot; return its serial (or None)."""
    emulator_bin = _emulator_bin()
    try:
        listed = subprocess.run([emulator_bin, "-list-avds"], capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        listed = ""
    candidates = [a.strip() for a in listed.splitlines() if a.strip() and _avd_is_play_store(a.strip())]
    if not candidates:
        return None
    avd = candidates[0]
    print(f"--open-emu: no emulator running; launching Play Store AVD '{avd}' (can take a minute) ...")
    before = set(_running_emulators(adb))
    try:
        subprocess.Popen([emulator_bin, "-avd", avd, "-no-boot-anim"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError as e:
        print(f"--open-emu: couldn't launch the emulator ({e}).")
        return None
    deadline = time.time() + 240
    serial = None
    while time.time() < deadline and not serial:
        time.sleep(3)
        new = set(_running_emulators(adb)) - before
        if new:
            serial = sorted(new)[0]
    if not serial:
        print("--open-emu: the emulator didn't come online in time.")
        return None
    print(f"--open-emu: {serial} online; waiting for boot to finish ...")
    while time.time() < deadline:
        try:
            booted = subprocess.run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                    capture_output=True, text=True, timeout=10).stdout.strip()
        except subprocess.SubprocessError:
            booted = ""
        if booted == "1":
            return serial
        time.sleep(3)
    print(f"--open-emu: {serial} came online but didn't finish booting in time; trying anyway.")
    return serial


# uiautomator node whose text/content-desc is exactly the Play Store's install control. "Open"
# (already installed) and "Uninstall" deliberately don't match — those mean "not the new artifact yet".
_READY_BUTTON = re.compile(r'(?:text|content-desc)="(?:Install|Update)"', re.IGNORECASE)


def _play_shows_install_button(adb, serial):
    """True if the Play Store screen currently shows an Install/Update button. A freshly uploaded
    Internal App Sharing artifact is processed before the link resolves to it; until then the page
    shows only "Open" (the installed build). We can't see that state off-device — the downloadUrl
    just 302s to a Google login for any HTTP client — so we scrape the on-device UI instead."""
    try:
        subprocess.run([adb, "-s", serial, "shell", "uiautomator", "dump", "/sdcard/iasharing_ui.xml"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
        xml = subprocess.run([adb, "-s", serial, "shell", "cat", "/sdcard/iasharing_ui.xml"],
                             capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    return bool(_READY_BUTTON.search(xml))


def _open_on_emulator(url, pkg, timeout, interval):
    """--open-emu: fire the install link at a running Play Store emulator (launching one if none is
    running); otherwise print the adb command. The caller always prints the link itself too.

    A freshly uploaded Internal App Sharing artifact is processed before the link resolves to it;
    until that finishes, opening the link shows the *installed* build ("Open") instead of the new
    one ("Update"/"Install"). There's no off-device readiness signal (no API status field; the
    downloadUrl 302s to a Google login for any HTTP client), so we poll the on-device Play Store UI:
    fire the link, check for the Install/Update button, and if it's not there yet wait `interval`s
    and re-fire (re-firing also defeats any device-side cache), up to `timeout`s."""
    adb = _adb()

    def sample(who):
        return (f'adb -s {who} shell am force-stop {pkg} && '
                f'adb -s {who} shell am start -a android.intent.action.VIEW -d "{url}"')

    try:
        emus = _running_emulators(adb)
    except (OSError, subprocess.CalledProcessError) as e:
        print(f"--open-emu: couldn't run adb ({e}); open it manually:\n  {sample('<emulator>')}")
        return

    if len(emus) > 1:
        print(f"--open-emu: {len(emus)} emulators running ({', '.join(emus)}); pick one:\n  {sample('<emulator>')}")
        return

    if len(emus) == 1:
        serial = emus[0]
        if _avd_is_play_store(_running_emulator_avd(adb, serial)) is False:
            print(f"--open-emu: {serial} is not a Play Store image (no Google Play), so Internal App Sharing\n"
                  f"installs / Play Billing won't work there. Open it on a google_apis_playstore AVD instead:\n"
                  f"  {sample('<emulator>')}")
            return
    else:
        serial = _launch_play_store_avd(adb)
        if not serial:
            print(f"--open-emu: no emulator running and no Play Store (google_apis_playstore) AVD to launch.\n"
                  f"Create one, then open the link with:\n  {sample('<emulator>')}")
            return

    # Force-stop the target first: if it's foregrounded, the VIEW intent just re-foregrounds the app
    # instead of routing to the Play Store's Internal App Sharing page.
    subprocess.run([adb, "-s", serial, "shell", "am", "force-stop", pkg],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"--open-emu: sending the link to {serial}, polling up to {timeout}s for Play to process it ...")
    deadline = time.time() + timeout
    fired_ok = False
    while True:
        rc = subprocess.run([adb, "-s", serial, "shell", "am", "start",
                             "-a", "android.intent.action.VIEW", "-d", url],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
        if rc != 0:
            print(f"--open-emu: adb exited {rc}; open it manually:\n  {sample(serial)}")
            return
        fired_ok = True
        time.sleep(3)  # let the Play Store page render before scraping it
        if _play_shows_install_button(adb, serial):
            print(f"Ready — {serial} is showing Install/Update. Tap it to install "
                  "(enable Internal App Sharing in the Play Store if prompted).")
            return
        if time.time() >= deadline:
            break
        time.sleep(interval)
    if fired_ok:
        print(f"--open-emu: gave up after {timeout}s — the Store still isn't showing Install/Update, so\n"
              "the artifact probably hasn't finished processing (or the button text isn't in English).\n"
              f"The link is on screen; re-fire it in a moment (no rebuild):\n  {sample(serial)}")


def cmd_share(pkg, args):
    """Upload an APK/AAB to Internal App Sharing and print the install link.

    Unlike a track release, this does NOT consume a versionCode (the same code can be re-uploaded
    any number of times) and needs no release/rollout/track management — so it's the fast loop for
    iterating on a build that still needs Google's signature (e.g. to exercise Play Billing)."""
    if not os.path.isfile(args.artifact):
        sys.exit(f"Artifact not found: {args.artifact}\n"
                 f"Build it first (e.g. scripts/build-and-release.py --play-only) or pass the path.")
    is_aab = args.artifact.lower().endswith(".aab")
    print(f"Artifact: {args.artifact}\nPackage:  {pkg}\nTarget:   Internal App Sharing ({'bundle' if is_aab else 'APK'})")
    if args.dry_run:
        print("\nDry run -- not uploading.")
        return

    service = play_service()
    from googleapiclient.http import MediaFileUpload
    media = MediaFileUpload(args.artifact, mimetype="application/octet-stream", resumable=True)
    artifacts = service.internalappsharingartifacts()
    print("Uploading (this can take a while for an AAB)...")
    call = artifacts.uploadbundle if is_aab else artifacts.uploadapk
    result = call(packageName=pkg, media_body=media).execute()

    url = result.get('downloadUrl')
    print(f"\nDone. Internal App Sharing install link:\n\n  {url}\n")
    if args.open_emu:
        _open_on_emulator(url, pkg, args.open_emu_timeout, args.open_emu_interval)
    else:
        print("Open it on the device signed into an authorized account (an uploader, or an\n"
              "internal-app-sharing tester). The same versionCode can be re-uploaded any number of times.")


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--package", default=DEFAULT_PACKAGE,
                   help=f"application id (default: {DEFAULT_PACKAGE})")
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("status", help="show each track's releases and rollout state")
    s.set_defaults(func=cmd_status)

    u = sub.add_parser("upload", help="upload an AAB to a track")
    u.add_argument("aab", nargs="?", default=DEFAULT_AAB, help=f"path to the AAB (default: {DEFAULT_AAB})")
    u.add_argument("--track", default="internal", choices=TRACKS, help="release track (default: internal)")
    grp = u.add_mutually_exclusive_group()
    grp.add_argument("--submit", action="store_true",
                     help="submit as a full rollout (status=completed); default leaves a draft")
    grp.add_argument("--rollout", type=float, metavar="FRACTION",
                     help="submit as a staged rollout to this fraction of users (0 < FRACTION < 1)")
    u.add_argument("--dry-run", action="store_true", help="don't upload; just show what would happen")
    u.set_defaults(func=cmd_upload)

    r = sub.add_parser("rollout", help="change the current staged rollout on a track (no re-upload)")
    r.add_argument("action", metavar="FRACTION|complete|halt",
                   help="new rollout fraction (0<f<1), or 'complete' (100%%), or 'halt' (pause)")
    r.add_argument("--track", default="production", choices=TRACKS, help="release track (default: production)")
    r.add_argument("--dry-run", action="store_true", help="show current state and intended change only")
    r.set_defaults(func=cmd_rollout)

    sh = sub.add_parser("share", help="upload an APK/AAB to Internal App Sharing and print the install link")
    sh.add_argument("artifact", nargs="?", default=DEFAULT_AAB, help=f"path to the APK/AAB (default: {DEFAULT_AAB})")
    sh.add_argument("--dry-run", action="store_true", help="don't upload; just show what would happen")
    sh.add_argument("--open-emu", action="store_true",
                    help="after upload, send the link to a running Play Store emulator (launching one if "
                         "none is running), polling the on-device Play Store UI until it shows the "
                         "Install/Update button (i.e. Play has processed the new artifact); falls back to "
                         "printing the adb command if there's no single suitable emulator")
    sh.add_argument("--open-emu-timeout", type=int, default=120, metavar="SECONDS",
                    help="max seconds to poll for Play to process the artifact / show Install/Update "
                         "(default: 120)")
    sh.add_argument("--open-emu-interval", type=int, default=5, metavar="SECONDS",
                    help="seconds between poll attempts / re-fires while waiting (default: 5)")
    sh.set_defaults(func=cmd_share)

    args = p.parse_args()
    # Run from the repo root so the default AAB path resolves.
    os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args.func(args.package, args)


if __name__ == "__main__":
    main()
