import time
import pytest
from lulucamera_backend.runtime import GenerationRunner
from lulucamera_backend.editing import GenerationError


def echo(connection, event):
    while True:
        connection.recv()
        connection.send(("ok", False))


def stall(connection, event):
    connection.recv()
    time.sleep(30)


def test_inference_process_is_reused():
    runner = GenerationRunner(target=echo)
    try:
        runner.generate("a", "b", None, "c", "d", lambda: False, 5)
        pid = runner.process.pid
        runner.generate("a", "b", None, "c", "d", lambda: False, 5)
        assert runner.process.pid == pid
    finally:
        runner.close()


def test_timeout_kills_stuck_child_and_next_job_can_run():
    runner = GenerationRunner(target=stall)
    try:
        with pytest.raises(GenerationError, match="GENERATION_TIMEOUT"):
            runner.generate("a", "b", None, "c", "d", lambda: False, 1)
        assert runner.process is None
        runner.target = echo
        runner.generate("a", "b", None, "c", "d", lambda: False, 5)
    finally:
        runner.close()


def test_cancel_stops_child_without_waiting_for_model():
    runner = GenerationRunner(target=stall)
    started = time.monotonic()
    try:
        with pytest.raises(GenerationError, match="CANCELLED"):
            runner.generate("a", "b", None, "c", "d", lambda: time.monotonic() - started > .5, 10)
        assert runner.process is None
    finally:
        runner.close()
