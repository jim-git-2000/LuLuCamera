"""CI 测试只写临时目录，不接触真实模型和用户照片。"""
import atexit
import os
from tempfile import TemporaryDirectory

_storage = TemporaryDirectory(prefix="lulucamera-tests-")
atexit.register(_storage.cleanup)
os.environ["LULU_STORAGE_DIR"] = _storage.name
os.environ["LULU_GENERATOR"] = "fake"
