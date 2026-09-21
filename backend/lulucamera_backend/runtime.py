"""常驻推理子进程：复用模型，超时/取消时终止进程并在下一任务重建。"""
from __future__ import annotations

import atexit
import multiprocessing as mp
from pathlib import Path
import time
import os
import threading

from .editing import GenerationError


def _model_process(connection, cancel_event) -> None:
    parent = mp.parent_process()
    def watch_parent():
        while parent is not None and parent.is_alive():
            time.sleep(1)
        # Worker 被强制终止时，不留下持有显存的孤立推理进程。
        os._exit(1)
    threading.Thread(target=watch_parent, daemon=True).start()
    from .generators import create_generator
    try:
        while True:
            request = connection.recv()
            try:
                original, mask, reference, output, metadata = request
                create_generator().generate(Path(original), Path(mask), Path(reference) if reference else None,
                                            Path(output), Path(metadata), cancel_event.is_set)
                connection.send(("ok", False))
            except GenerationError as error:
                connection.send((error.code, error.retryable))
            except Exception:
                connection.send(("GENERATION_FAILED", True))
    except (EOFError, BrokenPipeError):
        pass
    finally:
        connection.close()


class GenerationRunner:
    def __init__(self, target=_model_process):
        self.context = mp.get_context("spawn")
        self.target = target
        self.process = None
        self.connection = None
        self.cancel_event = None
        atexit.register(self.close)

    def _start(self):
        if self.process is not None and self.process.is_alive():
            return
        self.close()
        parent, child = self.context.Pipe()
        event = self.context.Event()
        process = self.context.Process(target=self.target, args=(child, event), daemon=True)
        try:
            process.start()
        except Exception:
            parent.close(); child.close()
            raise
        child.close()
        self.connection, self.cancel_event, self.process = parent, event, process

    def generate(self, original, mask, reference, output, metadata, cancelled, timeout: float):
        if not 1 <= timeout <= 3600:
            raise GenerationError("INVALID_GENERATION_CONFIG")
        if cancelled():
            raise GenerationError("CANCELLED")
        self._start()
        self.cancel_event.clear()
        deadline = time.monotonic() + timeout
        try:
            self.connection.send(tuple(str(p) if p is not None else None for p in (original, mask, reference, output, metadata)))
            while True:
                if cancelled():
                    self.cancel_event.set()
                    self.close()
                    raise GenerationError("CANCELLED")
                if time.monotonic() >= deadline:
                    self.close()
                    raise GenerationError("GENERATION_TIMEOUT", True)
                if self.connection.poll(.2):
                    code, retryable = self.connection.recv()
                    if code != "ok":
                        self.close()  # OOM 等错误后不复用可能损坏的 GPU 上下文。
                        raise GenerationError(code, retryable)
                    return
                if not self.process.is_alive():
                    raise GenerationError("INFERENCE_PROCESS_EXITED", True)
        except (EOFError, BrokenPipeError, OSError) as error:
            self.close()
            raise GenerationError("INFERENCE_PROCESS_EXITED", True) from error

    def close(self):
        if self.process is not None:
            if self.process.is_alive():
                self.process.terminate()
                self.process.join(2)
                if self.process.is_alive():
                    self.process.kill()
                    self.process.join(2)
            self.process.close()
            self.process = None
        if self.connection is not None:
            self.connection.close()
            self.connection = None


runner = GenerationRunner()
