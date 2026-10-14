"""Cross-platform access to the OS keyring via the `keyring` library.

The `keyring` package selects the right backend per operating system:
  - macOS:   Keychain
  - Linux:   Secret Service (GNOME Keyring / KWallet, via secretstorage)
  - Windows: Credential Locker (note: small blob size limit; not recommended
             for the large base64 signing creds)

Install it with:
  Debian/Ubuntu: apt install python3-keyring
  macOS:         pip install keyring        (built-in Keychain backend)

Secrets are addressed by (SERVICE, name). Store one from a file -- which
preserves multi-line values -- using this module's CLI:
  python3 scripts/_keyring.py set release-creds < release-creds.toml
then delete the plaintext file. `get`/`del` subcommands are also provided.
"""
import sys

SERVICE = "session-android"


def _keyring():
    try:
        import keyring
        return keyring
    except ImportError:
        sys.exit("Python `keyring` library not found. Install it "
                 "(Debian/Ubuntu: `apt install python3-keyring`; macOS: `pip install keyring`).")


def get_secret(name, *, what):
    """Return the secret stored under (SERVICE, name), or exit with instructions."""
    value = _keyring().get_password(SERVICE, name)
    if not value:
        sys.exit(f"{what} not found in the keyring (service '{SERVICE}', name '{name}').\n"
                 f"Store it once (reads the value from stdin, then delete the plaintext file):\n"
                 f"  python3 scripts/_keyring.py set {name} < /path/to/secret")
    return value


def _main(argv):
    if len(argv) != 3 or argv[1] not in ("set", "get", "del"):
        sys.exit("usage: _keyring.py {set|get|del} <name>   (set reads the value from stdin)")
    action, name = argv[1], argv[2]
    kr = _keyring()
    if action == "set":
        kr.set_password(SERVICE, name, sys.stdin.read())
        print(f"Stored secret (service '{SERVICE}', name '{name}').")
    elif action == "get":
        value = kr.get_password(SERVICE, name)
        if value is None:
            sys.exit(f"No secret for (service '{SERVICE}', name '{name}').")
        sys.stdout.write(value)
    else:  # del
        kr.delete_password(SERVICE, name)
        print(f"Deleted secret (service '{SERVICE}', name '{name}').")


if __name__ == "__main__":
    _main(sys.argv)
