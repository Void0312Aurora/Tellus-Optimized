"""Controller contracts only; these tests do not substitute for a Minecraft restart."""
import importlib.util
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
runner_path = Path(__file__).with_name("run_native_persistence_roundtrip.py")
spec = importlib.util.spec_from_file_location("native_persistence_runner", runner_path)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunnerContracts(unittest.TestCase):
    def test_malformed_nbt_and_overlapping_source_paths_fail_admission(self):
        for raw in (b"", b"\x01", b"\x0a\x00\x00\x00extra"):
            with self.assertRaises(ValueError):
                runner.NBTReader(raw).root()
        with self.assertRaises(ValueError):
            runner.assert_separate(Path("source/world/run").resolve(), Path("source/world").resolve())

    def test_native_report_identity_is_not_replaceable_by_generic_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            args = types.SimpleNamespace(chunk_x=1, chunk_z=-2)
            original = dict(status="PASS", phase="capture", probeId="probe", syntheticSampler=False,
                            chunkX=1, chunkZ=-2, diskReadBeforeDemandLoad=True)
            path.write_text(json.dumps(original), encoding="utf-8")
            self.assertEqual(runner.check_report(path, "capture", args, "probe"), original)
            for key, wrong in (("probeId", "stale"), ("phase", "verify"), ("syntheticSampler", True),
                               ("chunkX", 2), ("diskReadBeforeDemandLoad", False)):
                path.write_text(json.dumps({**original, key: wrong}), encoding="utf-8")
                with self.subTest(key=key), self.assertRaises(ValueError):
                    runner.check_report(path, "capture", args, "probe")

    @unittest.skipUnless(os.name == "nt", "Windows drive admission contract")
    def test_cross_drive_run_is_rejected_before_world_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = runner_path.resolve().parents[1]
            if root.drive.lower() == project.drive.lower():
                self.skipTest("test TEMP and checkout use the same drive")
            args = ["--inspect-only", "--module", "mc1201", "--world-template", str(root),
                    "--cache-template", str(root), "--run-dir", str(root / "new"),
                    "--project-root", str(project), "--chunk-x", "1", "--chunk-z", "2", "--port", "25661",
                    "--source-origin", "controller fixture only", "--provenance-file", str(runner_path)]
            with patch.object(runner, "inspect_world") as inspect, patch.object(runner.shutil, "copytree") as copy:
                with self.assertRaisesRegex(ValueError, "share the checkout drive"):
                    runner.main(args)
                inspect.assert_not_called()
                copy.assert_not_called()

    def test_live_lifecycle_requires_all_nonempty_families_and_exact_native_transition(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            args = types.SimpleNamespace(chunk_x=1, chunk_z=-2, populate_lifecycle=True)
            counts = dict(block_entities=1, block_ticks=1, fluid_ticks=1, postProcessingOffsets=2)
            report = dict(status="PASS", phase="verify", probeId="probe", syntheticSampler=False,
                          chunkX=1, chunkZ=-2, diskReadBeforeDemandLoad=True,
                          restartedDiskLifecycleCounts=counts, materializedNativeLifecycleEqual=True,
                          liveChestInventoryAndWaterVerified=True)
            path.write_text(json.dumps(report), encoding="utf-8")
            self.assertEqual(runner.check_report(path, "verify", args, "probe"), report)
            for field in counts:
                path.write_text(json.dumps({**report, "restartedDiskLifecycleCounts": {**counts, field: 0}}), encoding="utf-8")
                with self.subTest(field=field), self.assertRaises(ValueError):
                    runner.check_report(path, "verify", args, "probe")
            for field in ("materializedNativeLifecycleEqual", "liveChestInventoryAndWaterVerified"):
                path.write_text(json.dumps({**report, field: False}), encoding="utf-8")
                with self.subTest(field=field), self.assertRaises(ValueError):
                    runner.check_report(path, "verify", args, "probe")

    def test_generate_requires_previously_absent_full_chunk_and_exact_native_save(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            args = types.SimpleNamespace(chunk_x=1, chunk_z=-2)
            report = dict(status="PASS", phase="generate", probeId="probe", syntheticSampler=False,
                          chunkX=1, chunkZ=-2, diskReadBeforeDemandLoad=True,
                          targetAbsentBeforeGeneration=True, generatedDiskMatchesMaterialized=True,
                          generatedBlockDigest="a" * 64, generatedBiomeDigest="b" * 64,
                          generatedNativeNBTDigest="c" * 64, generatedNativeStatus="minecraft:full")
            path.write_text(json.dumps(report), encoding="utf-8")
            self.assertEqual(runner.check_report(path, "generate", args, "probe"), report)
            for key, wrong in (("targetAbsentBeforeGeneration", False), ("generatedDiskMatchesMaterialized", False),
                               ("generatedBlockDigest", ""), ("generatedBiomeDigest", "partial"),
                               ("generatedNativeNBTDigest", "f" * 63), ("generatedNativeStatus", "minecraft:noise")):
                path.write_text(json.dumps({**report, key: wrong}), encoding="utf-8")
                with self.subTest(key=key), self.assertRaises(ValueError):
                    runner.check_report(path, "generate", args, "probe")

    def test_batch_recovery_requires_two_original_payloads_startup_replay_and_resumed_engine_saves(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            args = types.SimpleNamespace(chunk_x=1, chunk_z=-2)
            report = dict(status="PASS", phase="recover_batch", probeId="probe", syntheticSampler=False,
                          chunkX=1, chunkZ=-2, diskReadBeforeDemandLoad=True,
                          nativeTargetsUnloaded=True, batchCompleteOriginalNBTEqual=True, engineSavingResumed=True,
                          batchOriginalDigests=["a" * 64, "b" * 64],
                          startupRecoveryDiagnostics=["recovered native batch journal fixture.prepared"])
            path.write_text(json.dumps(report), encoding="utf-8")
            self.assertEqual(runner.check_report(path, "recover_batch", args, "probe"), report)
            for key, wrong in (("nativeTargetsUnloaded", False), ("batchCompleteOriginalNBTEqual", False),
                               ("engineSavingResumed", False), ("batchOriginalDigests", ["a" * 64]),
                               ("startupRecoveryDiagnostics", [])):
                path.write_text(json.dumps({**report, key: wrong}), encoding="utf-8")
                with self.subTest(key=key), self.assertRaises(ValueError):
                    runner.check_report(path, "recover_batch", args, "probe")

    @unittest.skipUnless(os.name == "nt", "Windows Job Object contract")
    def test_windows_owned_job_closing_terminates_the_assigned_process(self):
        job = runner.OwnedWindowsJob()
        process = subprocess.Popen([sys.executable, "-c", "import threading; threading.Event().wait(120)"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            job.assign(process)
            job.close()
            process.wait(timeout=10)
            self.assertIsNotNone(process.poll())
        finally:
            job.close()
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)

    def test_phase_waits_for_report_and_submits_stop_once_for_duplicate_pass(self):
        messages = queue.Queue()
        commands = []
        test = self
        class Job:
            def assign(self, process): pass
            def close(self): pass
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "evidence"
            evidence.mkdir()
            reports = root / "tellus/persistence"
            reports.mkdir(parents=True)
            args = types.SimpleNamespace(chunk_x=1, chunk_z=-2, port=25661, timeout=5, stop_timeout=2,
                                         project_root=root, dimension="tellus:earth_cube_net")
            class Output:
                def __iter__(self):
                    while True:
                        line = messages.get(timeout=2)
                        if line is None: return
                        yield line
                def close(self): pass
            class Input:
                def write(self, line):
                    commands.append(line)
                    if line == "stop\n":
                        process.returncode = 0
                        messages.put(None)
                    else:
                        test.assertIn("tellus persistence capture 1 -2", line)
                        (reports / "probe-capture.json").write_text(json.dumps(dict(status="PASS", phase="capture",
                            probeId="probe", syntheticSampler=False, chunkX=1, chunkZ=-2,
                            diskReadBeforeDemandLoad=True)), encoding="utf-8")
                        messages.put("TELLUS_PERSISTENCE_CAPTURE_PASS result=report\n")
                        messages.put("TELLUS_PERSISTENCE_CAPTURE_PASS\n")
                def flush(self): pass
                def close(self): pass
            class Process:
                pid = 99999999
                returncode = None
                def __init__(self): self.stdout, self.stdin = Output(), Input()
                def poll(self): return self.returncode
                def wait(self, timeout=None):
                    if self.returncode is None: raise subprocess.TimeoutExpired("mock", timeout)
                    return self.returncode
            process = Process()
            messages.put('Done (1.2s)! For help, type "help"\n')
            with patch.object(runner.subprocess, "Popen", return_value=process), \
                 patch.object(runner, "OwnedWindowsJob", Job), patch.object(runner, "port_free"):
                result = runner.run_phase(args, root, evidence, ["mock"], {}, "capture", "probe")
            self.assertEqual(result["exitCode"], 0)
            self.assertEqual(commands.count("stop\n"), 1)


if __name__ == "__main__":
    unittest.main()
