"""Build the validation-only probe with javac; never invokes Gradle or a server."""

from pathlib import Path
import argparse
import os
import shutil
import subprocess
import zipfile


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEFAULT_JDK = Path.home() / ".jdks" / "openjdk-25.0.2"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", type=Path, default=ROOT / "validation/classpath.txt")
    parser.add_argument("--jdk", type=Path, default=Path(os.environ.get("JAVA_HOME", str(DEFAULT_JDK))))
    parser.add_argument("--output", type=Path,
                        default=Path(r"C:\GPur-validation-20261009\validation\worldgen-plugin"))
    args = parser.parse_args()
    output = args.output.resolve()
    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    javac = args.jdk / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    if not javac.is_file():
        found = shutil.which("javac")
        if not found:
            raise SystemExit("javac not found; pass --jdk <JDK home>")
        javac = Path(found)
    classpath = args.classpath.read_text(encoding="utf-8-sig").strip()
    if not classpath:
        raise SystemExit(f"empty classpath: {args.classpath}")

    def quote(path: Path) -> str:
        return '"' + str(path.resolve()).replace("\\", "/").replace('"', '\\"') + '"'

    sources = sorted((HERE / "src/main/java").rglob("*.java"))
    if not sources:
        raise SystemExit("no probe sources found")
    argfile = output / "javac.args"
    arguments = ["--release", "21", "-encoding", "UTF-8", "-proc:none",
                 "-cp", '"' + classpath.replace("\\", "/").replace('"', '\\"') + '"',
                 "-d", quote(classes)]
    arguments.extend(quote(source) for source in sources)
    argfile.write_text("\n".join(arguments), encoding="utf-8")
    subprocess.run([str(javac), "@" + str(argfile)], check=True)

    artifact = output / "GPurWorldgenProbe.jar"
    with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED) as archive:
        for compiled in sorted(classes.rglob("*.class")):
            archive.write(compiled, compiled.relative_to(classes).as_posix())
        for resource in sorted((HERE / "src/main/resources").rglob("*")):
            if resource.is_file():
                archive.write(resource, resource.relative_to(HERE / "src/main/resources").as_posix())
    print(artifact)


if __name__ == "__main__":
    main()
