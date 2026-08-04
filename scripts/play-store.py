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
import sys

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

    print(f"\nDone. Internal App Sharing install link:\n\n  {result.get('downloadUrl')}\n\n"
          "Open it on the device signed into an authorized account (an uploader, or an\n"
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
    sh.set_defaults(func=cmd_share)

    args = p.parse_args()
    # Run from the repo root so the default AAB path resolves.
    os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args.func(args.package, args)


if __name__ == "__main__":
    main()
