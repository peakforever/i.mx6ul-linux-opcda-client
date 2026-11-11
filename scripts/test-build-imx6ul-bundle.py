#!/usr/bin/env python3
"""Verify bundle permissions with fictional credentials and the local pinned JRE."""

import pathlib
import shutil
import subprocess
import tarfile
import tempfile


def main():
    project = pathlib.Path(__file__).resolve().parent.parent
    runtime = project / "runtime/zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz"
    if not runtime.is_file():
        raise SystemExit("Prepare the pinned runtime archive first; see the deployment manual.")
    with tempfile.TemporaryDirectory(prefix="opc2ecu-bundle-test-") as directory:
        fixture = pathlib.Path(directory)
        for name in ("scripts", "config", "target", "runtime"):
            (fixture / name).mkdir()
        for name in ("build-imx6ul-bundle.sh", "opc2ecu-launcher.sh"):
            shutil.copyfile(project / "scripts" / name, fixture / "scripts" / name)
        (fixture / "runtime" / runtime.name).symlink_to(runtime)
        (fixture / "target/opcda-probe.jar").write_bytes(b"permission-test-placeholder")
        config = fixture / "config/points.json"
        config.write_text('{"server":{"password":"fictional-test-only"}}', encoding="utf-8")
        config.chmod(0o644)
        (fixture / "config/opc.properties").write_text("# test fixture\n", encoding="utf-8")
        command = ["sh", str(fixture / "scripts/build-imx6ul-bundle.sh")]
        bundle = fixture / "target/opc2ecu-imx6ul-armhf.tar.gz"
        for arguments in ([], [str(runtime)]):
            result = subprocess.run(command + arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            assert result.returncode == 0, "Fixture bundle build failed"
            assert bundle.stat().st_mode & 0o777 == 0o600, "Archive must be private"
            with tarfile.open(bundle) as archive:
                assert archive.getmember("opc2ecu/config/points.json").mode & 0o777 == 0o600
                assert archive.getmember("opc2ecu/bin/opc2ecu").mode & 0o100
            assert not list((fixture / "target").glob(".opc2ecu-bundle.*"))
            bundle.chmod(0o644)  # Next build must replace an older permissive output safely.
        original = bundle.read_bytes()
        config.unlink()
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert result.returncode != 0, "Missing configuration must fail"
        assert bundle.read_bytes() == original, "Failed build must preserve the previous archive"
        assert not list((fixture / "target").glob(".opc2ecu-bundle.*"))
    print("PASS: bundle permissions, default/explicit runtime, replacement and failure cleanup")


if __name__ == "__main__":
    main()
