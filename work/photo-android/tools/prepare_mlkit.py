#!/usr/bin/env python3
"""Pinned, public Maven dependency preparation for the bundled Chinese recognizer.

resolve downloads public dependencies and writes an origin/hash lock. prepare is
offline: it materializes cached AAR inputs for the existing direct SDK build.
Neither operation compiles, signs, installs, or runs the application.
"""
import argparse
import copy
import hashlib
import io
import json
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

APP = Path(__file__).resolve().parents[1]
LIB = APP / "libs" / "mlkit"
CACHE = LIB / "cache"
LOCK = LIB / "dependencies.lock.json"
ROOT = ("com.google.mlkit", "text-recognition-chinese", "16.0.1")
REPOSITORIES = ("https://dl.google.com/dl/android/maven2/", "https://repo.maven.apache.org/maven2/")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ANDROID = "{http://schemas.android.com/apk/res/android}"
POMS = {}
FETCHED = {}


def text(node, path, default=""):
    found = node.find(path, NS)
    return (found.text or "").strip() if found is not None else default


def sha(data):
    return hashlib.sha256(data).hexdigest()


def coordinate(key):
    return ":".join(key)


def fetch(key, extension):
    group, artifact, version = key
    relative = group.replace(".", "/") + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "." + extension
    output = CACHE / relative
    origin = output.with_suffix(output.suffix + ".origin.json")
    if output.exists() and origin.exists():
        meta = json.loads(origin.read_text())
        data = output.read_bytes()
        if sha(data) != meta["sha256"]:
            raise RuntimeError("Cached dependency changed: " + relative)
        FETCHED[relative] = meta
        return output
    errors = []
    for base in REPOSITORIES:
        url = base + relative
        try:
            output.parent.mkdir(parents=True, exist_ok=True)
            temporary = output.with_suffix(output.suffix + ".download")
            response = subprocess.run(["curl", "-fsSL", "--retry", "2", "--connect-timeout", "15", "--max-time", "180", "-w", "%{http_code}", "-o", str(temporary), url], capture_output=True, text=True)
            if response.returncode:
                if temporary.exists():
                    temporary.unlink()
                if response.stdout.strip() == "404":
                    continue
                raise RuntimeError("Public dependency download failed: " + url + " " + response.stderr.strip())
            data = temporary.read_bytes()
            temporary.replace(output)
            meta = {"path": "cache/" + relative, "url": url, "sha256": sha(data), "bytes": len(data)}
            origin.write_text(json.dumps(meta, indent=2) + "\n")
            FETCHED[relative] = meta
            return output
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            errors.append(str(error.code))
    raise RuntimeError("Dependency not found in public repositories: " + relative)


def interpolate(value, properties):
    for _ in range(20):
        replaced = re.sub(r"\$\{([^}]+)\}", lambda match: properties.get(match.group(1), match.group(0)), value)
        if replaced == value:
            return value
        value = replaced
    raise RuntimeError("Cyclic Maven property: " + value)


def exact_version(value):
    if value.startswith("[") and value.endswith("]") and "," not in value:
        value = value[1:-1]
    if not value or "${" in value or any(c in value for c in "[],()") or value.endswith("+"):
        raise RuntimeError("Unpinned Maven version requires an explicit decision: " + value)
    return value


def pom(key):
    if key in POMS:
        return POMS[key]
    path = fetch(key, "pom")
    tree = ET.fromstring(path.read_bytes())
    parent_node = tree.find("m:parent", NS)
    parent = {"properties": {}, "management": {}, "dependencies": [], "licenses": []}
    if parent_node is not None:
        parent_key = tuple(text(parent_node, "m:" + name) for name in ("groupId", "artifactId", "version"))
        parent = pom(parent_key)
    props = dict(parent["properties"])
    props.update({"project.groupId": key[0], "pom.groupId": key[0], "project.artifactId": key[1], "project.version": key[2], "pom.version": key[2]})
    props_node = tree.find("m:properties", NS)
    if props_node is not None:
        props.update({child.tag.split("}")[-1]: (child.text or "").strip() for child in props_node})
    for _ in range(5):
        props = {name: interpolate(value, props) for name, value in props.items()}
    management = dict(parent["management"])

    def dependency(node, managed=False):
        group = interpolate(text(node, "m:groupId"), props)
        artifact = interpolate(text(node, "m:artifactId"), props)
        version = interpolate(text(node, "m:version", management.get((group, artifact), "")), props)
        scope = text(node, "m:scope", "compile")
        optional = text(node, "m:optional") == "true"
        extension = text(node, "m:type", "jar")
        if scope in ("test", "provided", "system") or optional:
            return None
        if text(node, "m:classifier"):
            raise RuntimeError("Unsupported dependency classifier: " + coordinate(key))
        exclusions = [(text(ex, "m:groupId"), text(ex, "m:artifactId")) for ex in node.findall("m:exclusions/m:exclusion", NS)]
        return {"key": (group, artifact, exact_version(version)), "scope": scope, "type": extension, "exclusions": exclusions}

    for node in tree.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
        dep = dependency(node, True)
        if dep is None:
            continue
        if dep["scope"] == "import" and dep["type"] == "pom":
            management.update(pom(dep["key"])["management"])
        else:
            management[dep["key"][:2]] = dep["key"][2]
    dependencies = list(parent["dependencies"])
    for node in tree.findall("m:dependencies/m:dependency", NS):
        dep = dependency(node)
        if dep is not None:
            dependencies = [old for old in dependencies if old["key"][:2] != dep["key"][:2]]
            dependencies.append(dep)
    licenses = [{"name": text(node, "m:name"), "url": text(node, "m:url")} for node in tree.findall("m:licenses/m:license", NS)] or parent["licenses"]
    result = {"packaging": text(tree, "m:packaging", "jar"), "dependencies": dependencies, "properties": props, "management": management, "licenses": licenses}
    POMS[key] = result
    return result


def version_order(value):
    # All chosen versions are fixed Maven release versions, never dynamic ranges.
    return tuple((1, int(part)) if part.isdigit() else (0, part) for part in re.findall(r"\d+|[A-Za-z]+", value))


def resolve():
    CACHE.mkdir(parents=True, exist_ok=True)
    chosen = {ROOT[:2]: ROOT[2]}
    for _ in range(30):
        requirements = {ROOT[:2]: {ROOT[2]}}
        todo = [(ROOT, frozenset())]
        visited = set()
        while todo:
            requested, excluded = todo.pop()
            ga = requested[:2]
            if ga in excluded or (ga[0], "*") in excluded or ("*", "*") in excluded:
                continue
            requirements.setdefault(ga, set()).add(requested[2])
            selected = (ga[0], ga[1], chosen.get(ga, requested[2]))
            marker = (selected, excluded)
            if marker in visited:
                continue
            visited.add(marker)
            for dep in pom(selected)["dependencies"]:
                todo.append((dep["key"], excluded | frozenset(dep["exclusions"])))
        next_chosen = {ga: max(versions, key=version_order) for ga, versions in requirements.items()}
        if next_chosen == chosen:
            break
        chosen = next_chosen
    else:
        raise RuntimeError("Maven dependency selection did not converge")
    artifacts = []
    for ga, version in sorted(chosen.items()):
        key = ga + (version,)
        details = pom(key)
        packaging = details["packaging"]
        if packaging not in ("jar", "aar", "pom"):
            raise RuntimeError("Unsupported Maven packaging: " + coordinate(key) + " " + packaging)
        if packaging == "pom":
            continue
        path = fetch(key, packaging)
        meta = json.loads(path.with_suffix(path.suffix + ".origin.json").read_text())
        artifacts.append({"coordinate": coordinate(key), "packaging": packaging, "file": meta, "licenses": details["licenses"]})
        print("Downloaded " + coordinate(key), flush=True)
    lock = {"schema": 1, "root": coordinate(ROOT), "repositories": REPOSITORIES, "selection": "highest fixed transitive version; optional/test/provided dependencies omitted; path exclusions retained", "artifacts": artifacts, "metadata": sorted(FETCHED.values(), key=lambda item: item["path"])}
    LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n")
    prepare(LIB / "prepared", lock)
    print("Prepared " + str(len(artifacts)) + " runtime artifacts in " + str(LIB), flush=True)


def safe_write(destination, relative, content):
    output = destination / relative
    if output.exists() and output.read_bytes() != content:
        raise RuntimeError("Conflicting dependency package entry: " + str(relative))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(content)


def merge_element(parent, incoming):
    name = incoming.get(ANDROID + "name")
    old = next((node for node in parent if node.tag == incoming.tag and node.get(ANDROID + "name") == name), None)
    if old is None:
        parent.append(copy.deepcopy(incoming))
    else:
        for key, value in incoming.attrib.items():
            if key in old.attrib and old.attrib[key] != value:
                raise RuntimeError("Conflicting dependency manifest attribute: " + str(name) + " " + key)
            old.set(key, value)
        for child in incoming:
            merge_element(old, child)


def jar_resources(destination, stem, data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for info in jar.infolist():
            name = info.filename
            if info.is_dir() or name.endswith(".class") or name == "META-INF/MANIFEST.MF" or re.search(r"META-INF/[^/]+\.(?:SF|RSA|DSA|EC)$", name, re.I):
                continue
            if ".." in Path(name).parts or name.startswith("/"):
                raise RuntimeError("Unexpected unsafe JAR entry")
            content = jar.read(name)
            if "/proguard/" in name or re.search(r"(?:^|/)(?:NOTICE|LICENSE|COPYING|DEPENDENCIES)(?:[._-].*)?$", name, re.I):
                safe_write(destination, Path("notices") / stem / name, content)
            elif name.startswith("META-INF/services/"):
                target = destination / "java-resources" / name
                lines = target.read_text().splitlines() if target.exists() else []
                lines += content.decode("utf-8").splitlines()
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("\n".join(dict.fromkeys(lines)) + "\n")
            else:
                safe_write(destination, Path("java-resources") / name, content)


def prepare(destination, lock=None):
    if lock is None:
        if not LOCK.is_file():
            raise RuntimeError("Missing locked OCR dependencies; run tools/prepare_mlkit.py resolve explicitly")
        lock = json.loads(LOCK.read_text())
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    manifest = ET.Element("manifest")
    application = ET.SubElement(manifest, "application")
    jars, resources, packages, notices, symbols = [], [], set(), [], {}
    for artifact in lock["artifacts"]:
        record = artifact["file"]
        archive = LIB / record["path"]
        if not archive.is_file():
            raise RuntimeError("Missing locked OCR dependency: " + record["path"])
        data = archive.read_bytes()
        if sha(data) != record["sha256"]:
            raise RuntimeError("Locked OCR dependency hash changed: " + record["path"])
        stem = artifact["coordinate"].replace(":", "-")
        notices.append({"coordinate": artifact["coordinate"], "source": record["url"], "sha256": record["sha256"], "licenses": artifact["licenses"]})
        if artifact["packaging"] == "jar":
            relative = Path("jars") / (stem + ".jar")
            safe_write(destination, relative, data)
            jar_resources(destination, stem, data)
            jars.append(str(relative))
            continue
        with zipfile.ZipFile(archive) as aar:
            aar_manifest = ET.fromstring(aar.read("AndroidManifest.xml"))
            package = aar_manifest.get("package")
            if package:
                packages.add(package)
                if "R.txt" in aar.namelist():
                    symbols.setdefault(package, set()).update(line.split()[1] + ":" + line.split()[2] for line in aar.read("R.txt").decode().splitlines() if line.strip())
            for node in aar_manifest:
                if node.tag == "application":
                    for child in node:
                        merge_element(application, child)
                elif node.tag not in ("uses-sdk",):
                    merge_element(manifest, node)
            resource_root = Path("res") / stem
            has_resources = False
            for info in aar.infolist():
                name = info.filename
                if info.is_dir():
                    continue
                parts = Path(name).parts
                if ".." in parts or name.startswith("/"):
                    raise RuntimeError("Unexpected unsafe AAR entry")
                if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                    relative = Path("jars") / (stem + "-" + name.replace("/", "-"))
                    safe_write(destination, relative, aar.read(name))
                    jar_resources(destination, stem, aar.read(name))
                    jars.append(str(relative))
                elif name.startswith("res/"):
                    safe_write(destination, resource_root / name[4:], aar.read(name))
                    has_resources = True
                elif name.startswith("assets/"):
                    safe_write(destination, Path(name), aar.read(name))
                elif name.startswith("jni/") and name.endswith(".so"):
                    safe_write(destination, Path("native/lib") / name[4:], aar.read(name))
                elif re.search(r"(?:^|/)(?:NOTICE|LICENSE|COPYING)(?:[._-].*)?$", name, re.I) or name.startswith("third_party_licenses") or name.endswith(".properties") or name in ("R.txt", "proguard.txt"):
                    safe_write(destination, Path("notices") / stem / name, aar.read(name))
            if has_resources:
                resources.append(str(resource_root))
    ET.register_namespace("android", ANDROID[1:-1])
    ET.ElementTree(manifest).write(destination / "manifest-contributions.xml", encoding="utf-8", xml_declaration=True)
    (destination / "inputs.json").write_text(json.dumps({"jars": jars, "resources": resources, "packages": sorted(packages)}, indent=2) + "\n")
    (destination / "symbols.json").write_text(json.dumps({package: sorted(names) for package, names in symbols.items()}, indent=2) + "\n")
    (destination / "dependency-notices.json").write_text(json.dumps(notices, indent=2, ensure_ascii=False) + "\n")
    (destination / "jars.txt").write_text("\n".join(jars) + "\n")
    (destination / "resources.txt").write_text("\n".join(resources) + "\n")
    (destination / "packages.txt").write_text(":".join(sorted(packages)) + "\n")
    # Put the upstream notices into the APK without uploading any application data.
    notice_assets = destination / "assets" / "mlkit_dependency_notices"
    notice_assets.mkdir(parents=True, exist_ok=True)
    shutil.copy2(destination / "dependency-notices.json", notice_assets / "dependencies.json")
    if (destination / "notices").exists():
        shutil.copytree(destination / "notices", notice_assets / "upstream", dirs_exist_ok=True)


def generate_r(prepared, linked_symbols, generated):
    entries = {}
    for line in linked_symbols.read_text().splitlines():
        kind, resource_type, name, value = line.split(None, 3)
        entries[resource_type + ":" + name] = (kind, resource_type, name, value)
    for package, names in json.loads((prepared / "symbols.json").read_text()).items():
        if not names:
            continue
        groups = {}
        for name in names:
            if name not in entries:
                raise RuntimeError("Library resource absent from linked application: " + package + " " + name)
            kind, resource_type, field, value = entries[name]
            groups.setdefault(resource_type, []).append("        public static final " + kind + " " + field + " = " + value + ";")
        lines = ["package " + package + ";", "public final class R {"]
        for resource_type, fields in sorted(groups.items()):
            lines.append("    public static final class " + resource_type + " {")
            lines.extend(fields)
            lines.append("    }")
        lines.append("}")
        target = generated / package.replace(".", "/") / "R.java"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("resolve", "prepare", "rjava"))
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--symbols", type=Path)
    parser.add_argument("--generated", type=Path)
    args = parser.parse_args()
    try:
        if args.operation == "resolve":
            resolve()
        elif args.operation == "prepare":
            prepare(args.destination or LIB / "prepared")
        else:
            if not args.symbols or not args.generated:
                raise RuntimeError("rjava requires --symbols and --generated")
            generate_r(args.destination or LIB / "prepared", args.symbols, args.generated)
    except Exception as error:
        print("OCR dependency preparation failed: " + str(error), file=sys.stderr)
        sys.exit(1)
