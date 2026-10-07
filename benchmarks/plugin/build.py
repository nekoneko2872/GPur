"""Build only this plugin with javac; never changes or launches the server."""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath', type=Path, default=ROOT / 'validation/classpath.txt')
    parser.add_argument('--jdk', type=Path, default=Path(os.environ.get('JAVA_HOME', str(Path.home() / '.jdks/openjdk-25.0.2'))))
    parser.add_argument('--output', type=Path, default=ROOT / 'validation/benchmark-plugin')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes = output / 'classes'
    # Do not delete user-selected directories; old classes cannot affect source compilation.
    classes.mkdir(exist_ok=True)
    executable = args.jdk / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
    if not executable.is_file():
        found = shutil.which('javac')
        if not found:
            raise SystemExit('javac not found; pass --jdk <JDK home>')
        executable = Path(found)
    cp = args.classpath.read_text(encoding='utf-8-sig').strip()
    sources = sorted((HERE / 'src/main/java').rglob('*.java'))
    def quote(value):
        return '"' + str(value).replace('\\', '/').replace('"', '\\"') + '"'
    arguments = ['--release', '21', '-encoding', 'UTF-8', '-proc:none', '-cp', quote(cp), '-d', quote(classes)]
    arguments += [quote(source) for source in sources]
    argfile = output / 'javac.args'
    argfile.write_text('\n'.join(arguments), encoding='utf-8')
    subprocess.run([str(executable), '@' + str(argfile)], check=True)
    artifact = output / 'GPurBench.jar'
    with zipfile.ZipFile(artifact, 'w', zipfile.ZIP_DEFLATED) as archive:
        for compiled in sorted(classes.rglob('*.class')):
            archive.write(compiled, compiled.relative_to(classes).as_posix())
        for resource in sorted((HERE / 'src/main/resources').rglob('*')):
            if resource.is_file():
                archive.write(resource, resource.relative_to(HERE / 'src/main/resources').as_posix())
    print(artifact)

if __name__ == '__main__':
    main()
