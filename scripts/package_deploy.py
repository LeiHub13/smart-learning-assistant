#!/usr/bin/env python3
"""Build the backend JAR and frontend dist from a commit, then package the server deployment bundle."""

import argparse
import gzip
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parent.parent

# 发布包里保留的运行期文件：编排、脚本、文档和 AI 服务源码（服务器按源码构建）。
RUNTIME = (
    "docker-compose.yml",
    "deploy.sh",
    ".env.example",
    "README.md",
    "DEPLOYMENT.md",
    "ai-service",
)

# 只有 Dockerfile、nginx.conf 和已编译产物需要进入后端/前端构建上下文。
BACKEND_FILES = ("Dockerfile", ".dockerignore")
FRONTEND_FILES = ("Dockerfile", "nginx.conf", ".dockerignore")


def git_output(*args: str) -> str:
    result = subprocess.run(
        ("git", "-C", str(ROOT), *args),
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout.decode("utf-8").strip()


def find_tool(name: str) -> str:
    found = shutil.which(name)
    if not found:
        raise RuntimeError(f"未找到 {name}；本机打包需要 JDK 17、Maven 和 Node 20")
    return found


def extract(sha: str, paths: tuple[str, ...], dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    with (dest / "commit.tar").open("wb") as stream:
        subprocess.run(
            ("git", "-C", str(ROOT), "archive", "--format=tar", sha, *paths),
            check=True,
            stdout=stream,
        )
    with tarfile.open(dest / "commit.tar") as archive:
        archive.extractall(dest, filter="data")
    (dest / "commit.tar").unlink()


def build_backend(source: Path) -> Path:
    mvn = find_tool("mvn")
    subprocess.run(
        (
            mvn,
            "-q",
            "-B",
            "-s",
            str(source / "backend" / "mvn-settings.xml"),
            "-f",
            str(source / "backend" / "pom.xml"),
            "package",
            "-DskipTests",
        ),
        check=True,
    )
    jars = sorted((source / "backend" / "target").glob("*.jar"))
    jars = [jar for jar in jars if not jar.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars:
        raise RuntimeError("Maven 未产出可运行 JAR")
    return jars[0]


def build_frontend(source: Path) -> Path:
    npm = find_tool("npm")
    frontend = source / "frontend"
    subprocess.run((npm, "ci", "--no-audit", "--no-fund"), cwd=frontend, check=True)
    subprocess.run((npm, "run", "build"), cwd=frontend, check=True)
    dist = frontend / "dist"
    if not (dist / "index.html").is_file():
        raise RuntimeError("前端构建未产出 dist/index.html")
    return dist


def assemble(sha: str, source: Path, jar: Path, dist: Path, stage: Path) -> None:
    for name in RUNTIME:
        origin = source / name
        if origin.is_dir():
            shutil.copytree(origin, stage / name)
        else:
            shutil.copy2(origin, stage / name)

    backend = stage / "backend"
    backend.mkdir(parents=True)
    for name in BACKEND_FILES:
        shutil.copy2(source / "backend" / name, backend / name)
    shutil.copy2(jar, backend / "app.jar")

    frontend = stage / "frontend"
    frontend.mkdir(parents=True)
    for name in FRONTEND_FILES:
        shutil.copy2(source / "frontend" / name, frontend / name)
    shutil.copytree(dist, frontend / "dist")

    (stage / "RELEASE_SHA").write_text(sha + "\n", encoding="ascii")


def package(output: Path, ref: str) -> str:
    sha = git_output("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError(f"Expected a 40-character commit SHA, got {sha!r}")

    output = output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(
        prefix=".learning-assistant-package-", dir=output.parent
    ) as workdir:
        work = Path(workdir)
        source = work / "source"
        extract(sha, ("backend", "frontend", *RUNTIME), source)

        jar = build_backend(source)
        dist = build_frontend(source)

        stage = work / "stage"
        stage.mkdir()
        assemble(sha, source, jar, dist, stage)

        tar_path = work / "release.tar"
        with tarfile.open(tar_path, mode="w") as archive:
            for entry in sorted(stage.iterdir()):
                archive.add(entry, arcname=entry.name, recursive=True)

        temporary_output = None
        try:
            with tempfile.NamedTemporaryFile(
                dir=output.parent, prefix=f".{output.name}.", suffix=".tmp", delete=False
            ) as stream:
                temporary_output = Path(stream.name)
                with gzip.GzipFile(fileobj=stream, mode="wb", filename="", mtime=0) as compressed:
                    with tar_path.open("rb") as archive:
                        shutil.copyfileobj(archive, compressed)
            os.replace(temporary_output, output)
        finally:
            if temporary_output is not None:
                temporary_output.unlink(missing_ok=True)

    return sha


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Path for the .tar.gz deployment package")
    parser.add_argument("--ref", default="HEAD", help="Committed Git ref to package (default: HEAD)")
    args = parser.parse_args()
    try:
        sha = package(args.output, args.ref)
    except (
        OSError,
        RuntimeError,
        subprocess.CalledProcessError,
        ValueError,
        tarfile.TarError,
    ) as exc:
        parser.exit(1, f"Packaging failed: {exc}\n")
    print(f"Packaged commit {sha} into {args.output}")


if __name__ == "__main__":
    main()
