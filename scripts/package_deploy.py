#!/usr/bin/env python3
"""Package committed build contexts and deployment files for server builds."""

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
INCLUDE = (
    "backend",
    "ai-service",
    "frontend",
    "docker-compose.yml",
    "deploy.sh",
    ".env.example",
    "README.md",
    "DEPLOYMENT.md",
)


def git_output(*args: str) -> str:
    result = subprocess.run(
        ("git", "-C", str(ROOT), *args),
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout.decode("utf-8").strip()


def package(output: Path, ref: str) -> str:
    sha = git_output("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError(f"Expected a 40-character commit SHA, got {sha!r}")

    commit_time = int(git_output("show", "-s", "--format=%ct", sha))
    output = output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(
        prefix=".learning-assistant-package-", dir=output.parent
    ) as workdir:
        tar_path = Path(workdir) / "release.tar"
        with tar_path.open("wb") as tar_stream:
            subprocess.run(
                ("git", "-C", str(ROOT), "archive", "--format=tar", sha, *INCLUDE),
                check=True,
                stdout=tar_stream,
            )

        release_sha = (sha + "\n").encode("ascii")
        with tarfile.open(tar_path, mode="a") as archive:
            entry = tarfile.TarInfo("RELEASE_SHA")
            entry.size = len(release_sha)
            entry.mode = 0o644
            entry.mtime = commit_time
            archive.addfile(entry, io.BytesIO(release_sha))

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
    except (OSError, subprocess.CalledProcessError, ValueError, tarfile.TarError) as exc:
        parser.exit(1, f"Packaging failed: {exc}\n")
    print(f"Packaged commit {sha} into {args.output}")


if __name__ == "__main__":
    main()
