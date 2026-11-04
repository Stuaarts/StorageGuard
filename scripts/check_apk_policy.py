"""Check the actual merged release manifest, including transitive library components."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "com.storageguard.app"
ALLOWED_PERMISSIONS = {
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}


def check_manifest(xml):
    root = ET.fromstring(xml)
    errors = []
    if root.get("package") != PACKAGE:
        errors.append("Unexpected application package")
    permissions = {
        item.get(ANDROID + "name")
        for item in root
        if item.tag in {"uses-permission", "uses-permission-sdk-23"}
    }
    unexpected = permissions - ALLOWED_PERMISSIONS
    if unexpected:
        errors.append("Unexpected permissions: " + ", ".join(sorted(unexpected)))
    application = root.find("application")
    if application is None:
        return ["Application element is missing"], {}
    if application.get(ANDROID + "allowBackup") != "false":
        errors.append("Application backups must be explicitly disabled")
    if application.get(ANDROID + "debuggable", "false") != "false":
        errors.append("Release application must not be debuggable")
    if application.get(ANDROID + "testOnly", "false") != "false":
        errors.append("Release application must not be testOnly")
    exported = []
    for component in application:
        if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        if component.get(ANDROID + "exported", "false") != "true":
            continue
        name = component.get(ANDROID + "name", "")
        if name.startswith("."):
            name = PACKAGE + name
        permission = component.get(ANDROID + "permission")
        exported.append({"kind": component.tag, "name": name, "permission": permission})
        launcher = component.tag == "activity" and name == PACKAGE + ".MainActivity"
        # apkanalyzer may print the compiled ActivityInfo.LAUNCH_SINGLE_TASK enum as 2.
        if launcher and component.get(ANDROID + "launchMode") not in {"singleTask", "2"}:
            errors.append("Launcher must preserve one cleanup activity using singleTask")
        profile_installer = (
            component.tag == "receiver"
            and name == "androidx.profileinstaller.ProfileInstallReceiver"
            and permission == "android.permission.DUMP"
        )
        if not (launcher or profile_installer):
            errors.append("Unexpected exported component: " + name)
    return errors, {"package": PACKAGE, "permissions": sorted(permissions), "exported": exported}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/release/app-release-unsigned.apk"))
    parser.add_argument("--apkanalyzer", default=shutil.which("apkanalyzer") or shutil.which("apkanalyzer.bat"))
    args = parser.parse_args()
    if not args.apkanalyzer:
        parser.error("Android command-line tools are required; add apkanalyzer to PATH or pass --apkanalyzer")
    try:
        result = subprocess.run([args.apkanalyzer, "manifest", "print", str(args.apk.resolve())],
                                check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stderr.strip() or "apkanalyzer failed to inspect the APK") from error
    errors, summary = check_manifest(result.stdout)
    summary.update({"apk": str(args.apk.resolve()), "passed": not errors, "errors": errors})
    print(json.dumps(summary, indent=2))
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
